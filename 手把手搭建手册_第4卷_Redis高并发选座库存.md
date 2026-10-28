# 手把手搭建手册（第 4 卷）
## Redis 高并发选座与库存：Lua 原子锁座、Redisson 看门狗、延迟关单、压测

> 前置：第 1~3 卷。第 3 卷用 MySQL 条件更新解决了「正确性」，本卷解决「高并发性能」与「坐票选座」：
> 开售瞬间上万请求不能直接打数据库，要把座位状态与余票放 Redis，用 **Lua 脚本保证锁座原子性**，用 **Redisson 看门狗**维持锁、用**延迟队列**自动关单，并保留 DB 作为最终一致的兜底。
> 新增包：`show.inventory`（库存/选座）、`common.job`（定时任务）。

---

## 目录
1. [整体方案与一致性策略](#1-整体方案与一致性策略)
2. [座位相关建表 SQL](#2-座位相关建表-sql)
3. [座位状态与 Redis Key 常量](#3-座位状态与-redis-key-常量)
4. [Redisson 配置](#4-redisson-配置)
5. [Lua 脚本：锁座/释放（原子性核心）](#5-lua-脚本锁座释放原子性核心)
6. [库存预热（开售前 DB → Redis）](#6-库存预热开售前-db--redis)
7. [座位图查询接口](#7-座位图查询接口)
8. [选座 Service（锁座/释放/查询）](#8-选座-service锁座释放查询)
9. [下单对接：锁座与订单绑定](#9-下单对接锁座与订单绑定)
10. [订单超时自动关单（延迟队列 + 定时兜底）](#10-订单超时自动关单延迟队列--定时兜底)
11. [锁死座位补偿任务](#11-锁死座位补偿任务)
12. [缓存三大问题与预热/失效策略](#12-缓存三大问题与预热失效策略)
13. [WebSocket 增量推送（选座实时变化）](#13-websocket-增量推送选座实时变化)
14. [JMeter 压测与验收](#14-jmeter-压测与验收)

---

## 1. 整体方案与一致性策略

读路径：座位坐标（静态）走 CDN/本地缓存；座位动态状态（可选/锁定/已售）走 **Redis Hash**。
写路径：

```
用户选座 → Lua 原子脚本(Redis)：校验FREE → 置LOCKED + 写锁key(带TTL) + 余票-1
        → 成功则给前端"锁座成功，5分钟内支付"
提交订单 → 校验锁属于本人 → 生成订单(待支付)，锁与订单绑定
支付成功 → Lua 置 SOLD（或消费消息异步落库）→ DB event_seat=SOLD
超时/取消 → Lua 回滚 LOCKED→FREE、删锁、余票+1；DB 补偿一致
```

一致性原则：**Redis 是实时真相，MySQL 是最终账本**。任何 Redis 异常都有定时任务对账补偿；支付成功这种关键节点必须落库。

为什么需要 Lua？「先 GET 判断 FREE，再 SET 成 LOCKED」是两步，并发下两个请求可能同时读到 FREE 都锁成功（超卖同一座）。Lua 在 Redis 内单线程一次性执行「判断+修改」，中间不会被其它命令插入，天然原子。

---

## 2. 座位相关建表 SQL

```sql
-- 场馆分区
CREATE TABLE show_venue_area (
  id BIGINT NOT NULL AUTO_INCREMENT,
  venue_id BIGINT NOT NULL,
  code VARCHAR(16) NOT NULL COMMENT '区码 A/B/VIP',
  name VARCHAR(32) NOT NULL,
  type VARCHAR(16) NOT NULL DEFAULT 'SEATED' COMMENT 'SEATED坐票 STAND站票',
  capacity INT NOT NULL DEFAULT 0,
  PRIMARY KEY(id), KEY idx_venue(venue_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 静态座位模板（一个场馆的座位坐标，可被多个场次复用）
CREATE TABLE show_seat (
  id BIGINT NOT NULL AUTO_INCREMENT,
  venue_id BIGINT NOT NULL,
  area_id BIGINT NOT NULL,
  seat_no VARCHAR(16) NOT NULL COMMENT '如 A-5-12',
  x INT NOT NULL COMMENT '座位图X坐标',
  y INT NOT NULL COMMENT '座位图Y坐标',
  PRIMARY KEY(id), KEY idx_area(area_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 场次维度座位动态状态（数据量大，后期按 event_id 分表）
CREATE TABLE show_event_seat (
  id BIGINT NOT NULL AUTO_INCREMENT,
  event_id BIGINT NOT NULL,
  seat_id BIGINT NOT NULL,
  area_id BIGINT NOT NULL,
  tier_id BIGINT NOT NULL,
  status VARCHAR(8) NOT NULL DEFAULT 'FREE' COMMENT 'FREE/LOCKED/SOLD',
  lock_owner VARCHAR(64) COMMENT '锁持有者 userId:orderNo',
  lock_expire DATETIME COMMENT '锁过期时间(兜底对账用)',
  version INT NOT NULL DEFAULT 0,
  PRIMARY KEY(id),
  UNIQUE KEY uk_event_seat(event_id, seat_id),
  KEY idx_event_status(event_id,status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='场次座位状态';
```

造一点座位测试数据（用存储过程或简单 INSERT，示例 A 区 5×5=25 座）：

```sql
INSERT INTO show_venue_area(id,venue_id,code,name,type,capacity) VALUES
 (1,1,'A','内场A区','SEATED',25),(2,1,'S','站票区','STAND',1000);

-- 生成 25 个座位（可写循环，这里示意前两排，实际用程序批量生成）
INSERT INTO show_seat(venue_id,area_id,seat_no,x,y) VALUES
 (1,1,'A-1-1',10,10),(1,1,'A-1-2',30,10),(1,1,'A-1-3',50,10),
 (1,1,'A-2-1',10,30),(1,1,'A-2-2',30,30),(1,1,'A-2-3',50,30);

-- 场次1 的座位实例（把模板复制为场次座位，绑定票档2=内场票）
INSERT INTO show_event_seat(event_id,seat_id,area_id,tier_id,status)
SELECT 1,id,area_id,2,'FREE' FROM show_seat WHERE area_id=1;
```

> 生产中「模板座位 → 场次座位」的复制在创建场次时由后台任务批量生成（`INSERT ... SELECT` 或分批 insert）。

---

## 3. 座位状态与 Redis Key 常量

路径：`show/inventory/enums/SeatStatus.java`

```java
package com.mars.concert.show.inventory.enums;

public enum SeatStatus {
    FREE("F"), LOCKED("L"), SOLD("S");
    private final String code;
    SeatStatus(String c){ this.code = c; }
    public String code(){ return code; }
}
```

扩展 `common/constant/RedisKeys.java`：

```java
String EVENT_SEAT_HASH = "mars:event:%s:seat";                 // Hash field=seatId value=F/L/S
String SEAT_LOCK = "mars:seat:lock:%s:%s";                    // eventId:seatId
String EVENT_TIER_STOCK = "mars:event:%s:tier:%s:stock";      // 站票/票档余票
String SEAT_LOCK_ORDER = "mars:seat:order:%s";                // orderNo -> 锁定座位集合(用于关单)
```

---

## 4. Redisson 配置

`pom.xml` 第 2 卷已加 redisson-spring-boot-starter。它会自动读 `spring.data.redis.*` 生成 `RedissonClient`，一般零配置。如需显式配置（可选），路径 `common/config/RedissonConfig.java`：

```java
@Configuration
public class RedissonConfig {
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient(@Value("${spring.data.redis.host}") String host,
                                         @Value("${spring.data.redis.port}") int port) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://" + host + ":" + port).setDatabase(0);
        return Redisson.create(config);
    }
}
```

> Redisson 提供：可重入锁 `RLock`（带**看门狗**自动续期，避免业务没执行完锁就过期）、`RDelayedQueue` 延迟队列、`RRateLimiter` 限流。本卷主要用前两者。

---

## 5. Lua 脚本：锁座/释放（原子性核心）

### 5.1 方式一：用 `DefaultRedisScript` 加载
路径：`show/inventory/lua/SeatLockScript.java`（也可把脚本放 `.lua` 资源文件）

```java
package com.mars.concert.show.inventory.lua;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SeatLockScript {

    /**
     * KEYS[1]=座位Hash  KEYS[2]=票档余票string
     * ARGV[1]=seatId  ARGV[2]=owner(userId:orderNo)  ARGV[3]=ttl秒
     * 返回码：1成功 0座位非空闲 -1已被他人锁定
     */
    private static final String LOCK_LUA = """
        local status = redis.call('HGET', KEYS[1], ARGV[1])
        if status == 'S' then return 0 end
        local lockKey = 'mars:seat:lock:'..string.match(KEYS[1],'event:(%d+):seat')..':'..ARGV[1]
        if status == 'L' then
          local owner = redis.call('GET', lockKey)
          if owner and owner ~= ARGV[2] then return -1 end
        end
        redis.call('HSET', KEYS[1], ARGV[1], 'L')
        redis.call('SET', lockKey, ARGV[2], 'EX', tonumber(ARGV[3]))
        redis.call('DECR', KEYS[2])
        return 1
        """;

    /**
     * 释放：KEYS[1]=Hash KEYS[2]=余票 ARGV[1]=seatId ARGV[2]=owner
     * 只有锁持有者能释放，返回1成功0非本人锁
     */
    private static final String RELEASE_LUA = """
        local lockKey = 'mars:seat:lock:'..string.match(KEYS[1],'event:(%d+):seat')..':'..ARGV[1]
        local owner = redis.call('GET', lockKey)
        if not owner or owner ~= ARGV[2] then return 0 end
        redis.call('HSET', KEYS[1], ARGV[1], 'F')
        redis.call('DEL', lockKey)
        redis.call('INCR', KEYS[2])
        return 1
        """;

    private final DefaultRedisScript<Long> lockScript = new DefaultRedisScript<>(LOCK_LUA, Long.class);
    private final DefaultRedisScript<Long> releaseScript = new DefaultRedisScript<>(RELEASE_LUA, Long.class);

    public Long lock(org.springframework.data.redis.core.RedisTemplate<String,Object> redis,
                     List<String> keys, String seatId, String owner, int ttl) {
        return redis.execute(lockScript, keys, seatId, owner, String.valueOf(ttl));
    }

    public Long release(org.springframework.data.redis.core.RedisTemplate<String,Object> redis,
                        List<String> keys, String seatId, String owner) {
        return redis.execute(releaseScript, keys, seatId, owner);
    }
}
```

> 脚本里动态拼 lockKey 用了 `string.match` 取 eventId；更稳妥的做法是把 lockKey 直接作为 `KEYS[3]` 传入（Redis Cluster 要求所有 key 在同 slot，单体无所谓）。教学中建议**把 lockKey 当 ARGV/KEY 显式传入**，避免脚本字符串处理出错。下面 Service 采用「每个座位单独脚本调用 + Redisson 聚合锁」的更直观写法。

### 5.2 站票（无座位）原子扣减
站票只需余票 key：

```lua
-- KEYS[1]=余票  ARGV[1]=qty
local left = tonumber(redis.call('GET', KEYS[1]) or '0')
if left < tonumber(ARGV[1]) then return -1 end
return redis.call('DECRBY', KEYS[1], ARGV[1])
```

---

## 6. 库存预热（开售前 DB → Redis）

路径：`show/inventory/service/InventoryWarmupService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryWarmupService {

    private final StringRedisTemplate stringRedis;   // 座位状态用纯字符串更简单
    private final EventSeatMapper eventSeatMapper;
    private final EventTierMapper eventTierMapper;

    /** 开售前调用（后台按钮 / 定时任务），把某场次座位与余票加载进 Redis */
    public void warmup(Long eventId) {
        // 1. 座位 Hash：批量 HSET
        String hashKey = String.format("mars:event:%d:seat", eventId);
        stringRedis.delete(hashKey);   // 重新预热前清旧
        List<EventSeat> seats = eventSeatMapper.selectList(
            Wrappers.<EventSeat>lambdaQuery().eq(EventSeat::getEventId, eventId));

        // 分批 pipeline 写入，避免一次太大
        List<EventSeat> batch = new ArrayList<>(500);
        for (EventSeat s : seats) {
            batch.add(s);
            if (batch.size() == 500) { flushSeat(hashKey, batch); batch.clear(); }
        }
        if (!batch.isEmpty()) flushSeat(hashKey, batch);

        // 2. 各票档余票（站票也用这个）
        List<EventTier> tiers = eventTierMapper.selectList(
            Wrappers.<EventTier>lambdaQuery().eq(EventTier::getEventId, eventId));
        for (EventTier t : tiers) {
            int remain = t.getTotalStock() - t.getSoldStock() - t.getLockedStock();
            stringRedis.opsForValue().set(
                String.format("mars:event:%d:tier:%d:stock", eventId, t.getTierId()),
                String.valueOf(remain));
        }
        log.info("场次 {} 库存预热完成，座位 {} 个，票档 {} 个", eventId, seats.size(), tiers.size());
    }

    private void flushSeat(String hashKey, List<EventSeat> batch) {
        stringRedis.executePipelined((RedisCallback<Object>) conn -> {
            for (EventSeat s : batch) {
                conn.hashCommands().hSet(
                    hashKey.getBytes(),
                    String.valueOf(s.getSeatId()).getBytes(),
                    s.getStatus().getBytes());
            }
            return null;
        });
    }
}
```

> 用 pipeline 批量写可减少网络往返，上万座位几秒内完成。`EventSeat` 实体、`EventSeatMapper` 参照第 3 卷实体风格创建（status 为 String）。

---

## 7. 座位图查询接口

返回：静态坐标 + 动态状态。路径：`show/inventory/model/vo/SeatMapVO.java`

```java
@Data
public class SeatMapVO {
    private Long eventId;
    private List<AreaVO> areas;
    @Data
    public static class AreaVO {
        private Long areaId;
        private String code;
        private String type;
        private List<SeatVO> seats;
        private Integer standRemain;   // 站票余票
    }
    @Data
    public static class SeatVO {
        private Long seatId;
        private String seatNo;
        private Integer x, y;
        private String status;         // F/L/S
        private Long tierId;
        private Boolean mine;          // 当前用户锁的
    }
}
```

Service 从 DB 查静态座位、从 Redis Hash 读状态合并（坐标可加本地缓存 Caffeine，第 7 卷讲多级缓存）。把 `L` 且锁 owner 是当前用户的标 `mine=true`。

Controller：
```java
@GetMapping("/events/{id}/seats")
public Result<SeatMapVO> seatMap(@PathVariable Long id,
                                 @CurrentUser(required=false) Long userId) {
    return Result.success(seatInventoryService.getSeatMap(id, userId));
}
```
> `@CurrentUser` 默认未登录会抛 401，座位图游客也能看，需给注解加 `boolean required() default true;`，解析器在 required=false 且无登录时返回 null。

---

## 8. 选座 Service（锁座/释放/查询）

路径：`show/inventory/service/SeatInventoryService.java`

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class SeatInventoryService {

    private final StringRedisTemplate redis;
    private final RedissonClient redisson;
    private final EventSeatMapper eventSeatMapper;
    private final EventTierMapper eventTierMapper;

    private static final int LOCK_TTL = 300;   // 5 分钟

    /**
     * 锁多个座位。用 Redisson 分布式锁串住同一场次的并发，再逐座执行原子 Lua，
     * 保证「一批座位要么全锁成功，要么全部不锁」。
     */
    public List<Long> lockSeats(Long userId, Long eventId, List<Long> seatIds) {
        String orderNo = OrderNoGenerator.next();   // 预生成，锁owner用它关联订单
        List<Long> locked = new ArrayList<>();

        // 对每个座位 hash 字段操作；用 eventId 维度的粗粒度锁串行化（学习期简单稳妥）
        RLock guard = redisson.getLock("mars:lock:event:" + eventId);
        boolean got = false;
        try {
            got = guard.tryLock(2, LOCK_TTL, TimeUnit.SECONDS);
            if (!got) throw new BusinessException(OrderErrorCode.EVENT_BUSY.getCode(), "当前选座人数过多，请重试");

            String hashKey = "mars:event:" + eventId + ":seat";
            for (Long seatId : seatIds) {
                String lockKey = "mars:seat:lock:" + eventId + ":" + seatId;
                // Lua：F→L + 设锁 + 余票-1（见第5节，可封装成脚本组件）
                Long r = seatLockScript.lock(redis, List.of(hashKey),
                        String.valueOf(seatId), userId + ":" + orderNo, LOCK_TTL);
                if (r == null || r != 1L) {
                    // 任一失败 → 回滚已锁
                    rollback(eventId, userId, orderNo, locked);
                    throw new BusinessException(r != null && r == -1
                            ? OrderErrorCode.SEAT_TAKEN.getCode(), "座位已被他人选中"
                            : OrderErrorCode.SEAT_TAKEN.getCode(), "座位不可选");
                }
                locked.add(seatId);
            }
            // 记录订单预占座位集合，供关单释放
            redis.opsForValue().set("mars:seat:order:" + orderNo,
                    userId + "|" + eventId + "|" + locked.stream().map(String::valueOf).collect(Collectors.joining(",")),
                    LOCK_TTL, TimeUnit.SECONDS);
            return locked;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        } finally {
            if (got && guard.isHeldByCurrentThread()) guard.unlock();
        }
    }

    /** 用户主动释放 */
    public void releaseSeats(Long userId, Long eventId, List<Long> seatIds, String orderNo) {
        for (Long seatId : seatIds) {
            String hashKey = "mars:event:" + eventId + ":seat";
            seatLockScript.release(redis, List.of(hashKey),
                    String.valueOf(seatId), userId + ":" + orderNo);
        }
        redis.delete("mars:seat:order:" + orderNo);
    }

    /** 关单/超时释放（系统身份，按 orderNo 找到座位） */
    public void releaseByOrderNo(String orderNo) {
        String val = (String) redis.opsForValue().get("mars:seat:order:" + orderNo);
        if (val == null) return;
        String[] parts = val.split("\\|");
        Long userId = Long.valueOf(parts[0]);
        Long eventId = Long.valueOf(parts[1]);
        if (parts.length < 3 || parts[2].isBlank()) return;
        List<Long> seatIds = Arrays.stream(parts[2].split(",")).map(Long::valueOf).toList();
        releaseSeats(userId, eventId, seatIds, orderNo);
    }

    private void rollback(Long eventId, Long userId, String orderNo, List<Long> locked) {
        releaseSeats(userId, eventId, locked, orderNo);
    }

    // getSeatMap(...)：DB 坐标 + Redis 状态合并，略（见第7节）
}
```

新增错误码（OrderErrorCode 或 SeatErrorCode）：`SEAT_TAKEN(3001,"座位已被选中")`、`EVENT_BUSY(3002,"系统繁忙")`。

> **看门狗说明**：上面用的是 `tryLock(waitTime, leaseTime, unit)`，显式给了 leaseTime=300，这种情况下**看门狗不生效**（指定了租期就不自动续）。要体验看门狗，可改用 `tryLock(waitTime, unit)`（不传 leaseTime），Redisson 默认 -1 启用看门狗，每 10 秒续到 30 秒，适合业务时长不确定的场景。锁座固定 5 分钟 TTL 更符合业务（必须释放给别人），所以这里显式租期是对的。

---

## 9. 下单对接：锁座与订单绑定

第 3 卷 `createOrder` 改造：
- 若 DTO 是「选座下单」（带 `seatIds`），先调用 `seatInventoryService.lockSeats` 得到预占，再把 orderNo 与锁绑定（lockSeats 已用预生成 orderNo）；
- 订单明细写 `seat_id`（给 ord_order_item 加 `seat_id BIGINT` 列）；
- 库存不再对站票 EventTier 扣 `sold`，而是锁座时 Redis 余票 -1；**支付成功**才把 DB `event_seat=SOLD`、EventTier.sold+1。
- 订单取消/超时：释放座位（第 10 节）。

为兼容站票，下单 Service 用策略模式：

```java
public interface InventoryStrategy {
    String type();                       // SEAT / STAND
    void lock(OrderContext ctx);         // 锁
    void release(String orderNo);        // 释放
    void confirmPaid(String orderNo);    // 支付成功确认
}
```
两个实现 `SeatInventoryStrategy`、`StandInventoryStrategy`，用 `Map<String,InventoryStrategy>` 注入按类型路由。这是「策略模式消除 if-else」的典型，也为以后加「VIP 包厢」等新库存类型留扩展点。

---

## 10. 订单超时自动关单（延迟队列 + 定时兜底）

### 10.1 下单成功投递延迟消息
在订单创建成功后（第 3 卷 createOrder 末尾，事务提交后执行）：

```java
@Service
@RequiredArgsConstructor
public class OrderDelayService {
    private final RedissonClient redisson;

    @PostConstruct
    public void init() {
        RBlockingQueue<String> blocking = redisson.getBlockingQueue("mars:order:timeout:block");
        RDelayedQueue<String> delayed = redisson.getDelayedQueue(blocking);
        // 消费者（见下）单独类；这里仅演示投递 API
    }

    public void sendCloseCheck(String orderNo, long delaySeconds) {
        RBlockingQueue<String> blocking = redisson.getBlockingQueue("mars:order:timeout:block");
        RDelayedQueue<String> delayed = redisson.getDelayedQueue(blocking);
        delayed.offer(orderNo, delaySeconds, TimeUnit.SECONDS);
    }
}
```

> Redisson 延迟队列原理：先把元素放 zset（按到期时间排序），后台任务把到期元素搬到目标 BlockingQueue，消费者用 take() 阻塞获取。你不用自己写轮询。

### 10.2 消费者
路径：`common/job/OrderTimeoutConsumer.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderTimeoutConsumer implements ApplicationRunner {

    private final RedissonClient redisson;
    private final OrderService orderService;
    private final SeatInventoryService seatInventoryService;

    @Override
    public void run(ApplicationArguments args) {
        Thread worker = new Thread(() -> {
            RBlockingQueue<String> q = redisson.getBlockingQueue("mars:order:timeout:block");
            redisson.getDelayedQueue(q);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    String orderNo = q.take();   // 阻塞直到有到期订单
                    orderService.closeIfTimeout(orderNo);  // 内部幂等：仅 WAIT_PAY 才关
                    seatInventoryService.releaseByOrderNo(orderNo);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    log.error("关单处理异常", e);
                }
            }
        }, "order-timeout-consumer");
        worker.setDaemon(true);
        worker.start();
    }
}
```

`OrderServiceImpl.closeIfTimeout`：
```java
@Transactional(rollbackFor = Exception.class)
public void closeIfTimeout(String orderNo) {
    Orders o = baseMapper.selectOne(Wrappers.<Orders>lambdaQuery().eq(Orders::getOrderNo, orderNo));
    if (o == null || !OrderStatus.WAIT_PAY.name().equals(o.getStatus())) return; // 幂等：已支付/已关跳过
    transit(o.getId(), OrderEvent.TIMEOUT_CANCEL, "SYSTEM");
    // 站票回补 EventTier 库存；坐票由 releaseByOrderNo 释放 Redis，并异步对齐 DB
}
```

### 10.3 为什么还要定时兜底
延迟消息可能因应用宕机、Redis 重启丢失（RDelayedQueue 依赖 Redis 持久化）。必须有一个**独立于消息的扫描任务**兜底：

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class OrderSweepJob {
    private final OrderService orderService;
    private final SeatInventoryService seatInventoryService;

    /** 每分钟扫描一次：过期时间已到、仍待支付的订单 */
    @Scheduled(fixedDelay = 60_000)
    public void sweep() {
        List<Orders> timeout = orderService.listExpiredWaitPay(50);
        for (Orders o : timeout) {
            try {
                orderService.closeIfTimeout(o.getOrderNo());
                seatInventoryService.releaseByOrderNo(o.getOrderNo());
            } catch (Exception e) { log.error("兜底关单失败 {}", o.getOrderNo(), e); }
        }
    }
}
```

启动类加 `@EnableScheduling`。`listExpiredWaitPay`：`status='WAIT_PAY' AND expire_time<NOW() LIMIT 50`。

> 双保险是分布式系统的常见设计：**实时通道（延迟队列）追求快，兜底通道（定时扫描）追求不丢**。后期也可用 XXL-JOB 做分布式调度，避免多实例重复扫描（加分布式锁或分片）。

---

## 11. 锁死座位补偿任务

异常场景：Redis 锁 TTL 到了但 DB 仍记 LOCKED、或 Redis 与 DB 不一致。定时对账：

```java
@Scheduled(fixedDelay = 120_000)
public void reconcileLocks() {
    // 查 DB 中 lock_expire 已过但 status=LOCKED 的座位
    List<EventSeat> stuck = eventSeatMapper.selectList(Wrappers.<EventSeat>lambdaQuery()
        .eq(EventSeat::getStatus, SeatStatus.LOCKED.name())
        .lt(EventSeat::getLockExpire, LocalDateTime.now())
        .last("LIMIT 200"));
    for (EventSeat s : stuck) {
        // 若对应订单已不存在/已取消 → Redis 与 DB 都置 FREE
        // 若订单已支付 → 置 SOLD（以订单为准）
        // 按业务规则对齐，略
    }
}
```

本卷 Redis 为主时 DB lock_expire 由锁座时写入；对账保证 Redis 丢数据后可从 DB 重建。

---

## 12. 缓存三大问题与预热/失效策略

结合本项目必须能复现并解释：
- **缓存穿透**：恶意请求不存在的 eventId。对策：参数先校验存在性；缓存空值（短 TTL 30s）或布隆过滤器（启动时加载所有有效 eventId）。
- **缓存击穿**：热点场次 key 在开售瞬间失效，大量请求同时回源 DB。对策：Redisson 单飞锁——只有一个线程查库重建，其它等待；座位数据用「永不过期 key + 开赛前主动预热 + 结束后清理」。
- **缓存雪崩**：大量 key 同 TTL 同时过期。对策：基础 TTL + 随机抖动（如 300s ± 60s）。
- **一致性**：采用 Cache Aside：支付成功先更新 DB，再删/改 Redis；座位这种强一致走 Lua 直接以 Redis 为准，不走旁路缓存。

缓存场次详情（第 3 卷 EventVO）示例（Spring Cache + Redisson 单飞）：

```java
@Cacheable(value = "event", key = "#id", unless = "#result==null")
public EventVO detail(Long id) { ... }

@CacheEvict(value = "event", key = "#id")
public void updateEvent(Long id, EventSaveDTO dto) { ... }
```

启动类加 `@EnableCaching`。

---

## 13. WebSocket 增量推送（选座实时变化）

第 6 卷会完整讲 WebSocket，这里先给选座需要的最小推送：当锁座/释放成功，向「订阅了该场次」的在线用户广播座位状态变化，前端只增量更新而不轮询。

- 配置：`@EnableWebSocket` + 注册端点 `/ws/event/{eventId}`（用 HandshakeInterceptor 取 token）。
- 锁座成功后：`websocketSender.send(eventId, Map.of("seatId",id,"status","L"));`
- 前端：
```javascript
const ws = new WebSocket(`ws://${location.host}/ws/event/${eventId}?token=${token}`);
ws.onmessage = e => { const m = JSON.parse(e.data); updateSeat(m.seatId, m.status); };
```
- 集群多实例时通过 Redis Pub/Sub 或 MQ fanout 广播（第 6 卷实现）。

---

## 14. JMeter 压测与验收

### 14.1 测试计划
1. 某场次某票档站票库存设 100；或 A 区 25 个座位。
2. JMeter 建 200 线程，每个线程发起一次「锁座/站票下单」，座位测试里让大家抢同 25 个座位（参数化随机 seatId 1~25）。
3. 加 HTTP Header `Authorization: Bearer <token>`（可先注册多个用户或临时放开鉴权压测）。
4. 监听：聚合报告（TPS、RT、错误率）、响应断言成功 code=0。

### 14.2 验收标准
- 站票：恰好 100 单成功，其余返回「余票不足」；Redis 余票=0，DB sold=100，**绝不为负**。
- 坐票：25 个座位每个最多被 1 人锁成功，绝不出现一座两锁；锁后 5 分钟未支付自动释放（可临时把 TTL 改 30 秒便于观察）。
- 重复点击（同幂等号）只生成一单。
- 关单：构造 1 单等待过期，延迟队列/定时任务将其关闭且库存回补。

### 14.3 对比实验（加深理解）
- 暂时绕过 Redis，直接让 200 线程打 MySQL 条件更新：观察 DB CPU、RT；能正确但吞吐受限。
- 用 Redis+Lua：吞吐显著上升、DB 压力极小。写下两组 TPS/RT 对比，就是很好的学习成果。

### 排查表
| 现象 | 原因/解决 |
|---|---|
| Lua 返回类型转换异常 | `DefaultRedisScript` 返回类型用 Long，脚本 return 数字 |
| 一座被两人锁 | 判断与修改没在同一 Lua；或没用原子脚本 |
| 锁不释放 | 检查 owner 匹配、releaseByOrderNo 的 key 拼接 |
| Redisson 锁报错 leaseTime | tryLock 参数是 (waitTime,leaseTime,unit) 三参；看门狗用两参 |
| 延迟消息重启丢失 | 必须配合第 10.3 定时兜底；Redis 开 AOF 持久化 |
| @Scheduled 不执行 | 启动类漏 @EnableScheduling |
| 多实例重复关单 | closeIfTimeout 已幂等（只关 WAIT_PAY）；分布式调度用 XXL-JOB 或 Redisson 锁 |
| 预热后余票不对 | 预热前清旧 key；total-sold-locked 计算口径统一 |

### Checklist
- [ ] 预热后 Redis Hash 座位状态与 DB 一致，余票正确
- [ ] 座位图接口返回坐标+F/L/S，本人锁定标 mine
- [ ] Lua 锁座原子，并发不出现一座两锁
- [ ] 锁 TTL 到期或用户取消后座位释放、余票回补
- [ ] 下单与锁座绑定；支付成功（第5卷）后置 SOLD
- [ ] 延迟队列自动关单；杀掉进程模拟丢消息时定时任务仍能兜底关闭
- [ ] 锁死补偿任务可对齐 Redis/DB
- [ ] JMeter 200 并发不超卖，记录 TPS/RT 对比数据

---

## 下一卷预告（第 5 卷：RabbitMQ 消息、支付与出票）
Docker 起 RabbitMQ；交换机/队列/死信/可靠投递（confirm/return/手动ACK）；模拟支付网关、异步回调验签与幂等；本地消息表保证最终一致；支付成功扇出（出票、加积分、发通知）；电子票二维码（zxing）与扫码核销；Spring Mail 邮件；订单 TTL 死信关单（与本卷 Redisson 方案对照）。
