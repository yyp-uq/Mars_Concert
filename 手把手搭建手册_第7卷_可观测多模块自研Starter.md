# 手把手搭建手册（第 7 卷）
## 可观测性、自研 Spring Boot Starter、多模块重构、自动化测试

> 前置：第 1~6 卷。本卷解决「工程化」问题：
> ① 指标/日志/链路可观测（Actuator + Micrometer + Prometheus + Grafana，全链路 traceId）；
> ② 把通用能力沉淀为**自研 spring-boot-starter**，真正搞懂自动装配；
> ③ 把单模块工程重构为多 Maven 模块（为第 8 卷拆微服务铺路）；
> ④ JUnit5 + Mockito + Testcontainers 单测与集成测试。

---

## 目录
1. [Actuator 健康检查与指标暴露](#1-actuator-健康检查与指标暴露)
2. [Micrometer 自定义业务指标](#2-micrometer-自定义业务指标)
3. [Prometheus + Grafana 大盘](#3-prometheus--grafana-大盘)
4. [统一 traceId（MDC + Filter + 线程池/MQ 透传）](#4-统一-traceidmdc--filter--线程池mq-透传)
5. [Logback JSON 与按天滚动](#5-logback-json-与按天滚动)
6. [自研 Starter 原理（自动装配机制）](#6-自研-starter-原理自动装配机制)
7. [实战：开发 mars-lock-starter（@DistributedLock）](#7-实战开发-mars-lock-starterdistributedlock)
8. [实战：开发 mars-log-starter（@OperationLog 通用化）](#8-实战开发-mars-log-starteroperationlog-通用化)
9. [单模块重构为多 Maven 模块](#9-单模块重构为多-maven-模块)
10. [JUnit5 + Mockito 单元测试](#10-junit5--mockito-单元测试)
11. [Testcontainers 集成测试](#11-testcontainers-集成测试)
12. [验收 Checklist](#12-验收-checklist)

---

## 1. Actuator 健康检查与指标暴露

### 1.1 依赖
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

### 1.2 配置（最小暴露 + 鉴权意识）
```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus,metrics   # 不要暴露 env/beans 等到公网
      base-path: /actuator
  endpoint:
    health:
      show-details: when_authorized   # 细节仅对有权限者
  metrics:
    tags:
      application: ${spring.application.name}
```

> 生产中 `/actuator/prometheus` 只允许内网/Prometheus 抓取，可在 Nginx 或 SecurityConfig 限制 IP/角色。

### 1.3 验证
- http://localhost:8080/actuator/health → `{"status":"UP"}`
- http://localhost:8080/actuator/prometheus → 大量指标文本（JVM、HTTP、Tomcat、自定义）

---

## 2. Micrometer 自定义业务指标

Micrometer 是 Spring Boot 的「指标门面」，提供 Counter/Gauge/Timer。路径示例 `order/metrics/OrderMetrics.java`：

```java
@Component
@RequiredArgsConstructor
public class OrderMetrics {
    private final MeterRegistry registry;
    private Counter created;
    private Counter stockFail;

    @PostConstruct
    public void init() {
        created = Counter.builder("mars_order_created_total")
                .description("下单成功总数").tag("channel","app").register(registry);
        stockFail = Counter.builder("mars_order_stock_fail_total")
                .description("库存不足次数").register(registry);
    }
    public void incCreated(){ created.increment(); }
    public void incStockFail(){ stockFail.increment(); }
}
```

在第 3 卷下单成功/库存不足处分别调用。也可用更简单的方式直接在业务里：
```java
registry.counter("mars_order_created_total").increment();
```

Timer 统计下单耗时：
```java
Timer timer = Timer.builder("mars_order_create_duration").register(registry);
timer.record(() -> orderService.createOrder(uid, dto));
```

Gauge（如待支付订单数）：
```java
registry.gauge("mars_order_wait_pay_count", atomicWaitPay);
```

---

## 3. Prometheus + Grafana 大盘

### 3.1 docker-compose（新建 `docker/observability/docker-compose.yml`）
```yaml
services:
  prometheus:
    image: prom/prometheus:v2.53.0
    ports: ["9090:9090"]
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
  grafana:
    image: grafana/grafana:11.1.0
    ports: ["3000:3000"]
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
```

`prometheus.yml`：
```yaml
global:
  scrape_interval: 5s
scrape_configs:
  - job_name: 'mars-platform'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['host.docker.internal:8080']
```
启动：`docker compose up -d`（在该目录）。

### 3.2 使用
1. http://localhost:9090 → Status/Targets 看到 mars-platform UP。
2. http://localhost:3000（admin/admin）→ Connections 添加 Prometheus 数据源 `http://prometheus:9090`。
3. New Dashboard 加面板，PromQL 示例：
   - QPS：`rate(http_server_requests_seconds_count{uri!~"/actuator.*"}[1m])`
   - 平均 RT：`rate(http_server_requests_seconds_sum[1m]) / rate(http_server_requests_seconds_count[1m])`
   - 下单成功速率：`rate(mars_order_created_total[1m])`
   - JVM 堆内存：`jvm_memory_used_bytes{area="heap"}`
   - 库存不足：`mars_order_stock_fail_total`

> Grafana 可直接导入现成 Dashboard 模板（如 Spring Boot Statistics ID: 12900、JVM Micrometer ID: 4701），再补自定义业务面板。

---

## 4. 统一 traceId（MDC + Filter + 线程池/MQ 透传）

目标：一条请求从进入到所有日志都带同一个 traceId，跨 HTTP/线程/MQ 也能串起来（微服务前先在单体做透传思想）。

### 4.1 入口 Filter 生成/接收 traceId
路径：`common/web/TraceIdFilter.java`
```java
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {
    public static final String TRACE_ID = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp,
                                    FilterChain chain) throws ServletException, IOException {
        // 上游有传则沿用（微服务间透传），否则生成
        String traceId = req.getHeader("X-Trace-Id");
        if (!StringUtils.hasText(traceId)) traceId = UUID.randomUUID().toString().replace("-","");
        MDC.put(TRACE_ID, traceId);
        resp.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(req, resp);
        } finally {
            MDC.clear();
        }
    }
}
```

### 4.2 logback 输出 traceId
在日志 pattern 加 `%X{traceId}`（见第 5 节）。

### 4.3 线程池透传
`@Async`/自定义线程池会丢失 MDC，用装饰器包装：
```java
public class MdcTaskDecorator implements TaskDecorator {
    @Override
    public Runnable decorate(Runnable runnable) {
        Map<String,String> ctx = MDC.getCopyOfContextMap();
        return () -> {
            if (ctx != null) MDC.setContextMap(ctx);
            try { runnable.run(); } finally { MDC.clear(); }
        };
    }
}

@Bean
public ThreadPoolTaskExecutor bizExecutor() {
    ThreadPoolTaskExecutor ex = new ThreadPoolTaskExecutor();
    ex.setCorePoolSize(4); ex.setMaxPoolSize(16); ex.setQueueCapacity(200);
    ex.setThreadNamePrefix("biz-");
    ex.setTaskDecorator(new MdcTaskDecorator());
    ex.initialize();
    return ex;
}
```
使用：`@Async("bizExecutor")`。

### 4.4 MQ 透传
发送时把 traceId 放进消息头：
```java
rabbitTemplate.convertAndSend(ex, rk, payload, m -> {
    m.getMessageProperties().setHeader("X-Trace-Id", MDC.get("traceId"));
    return m;
});
```
消费者开头取头放入 MDC：
```java
String tid = message.getMessageProperties().getHeader("X-Trace-Id");
if (tid != null) MDC.put("traceId", tid);
```
这样一条支付链路（下单→MQ→出票→通知）日志可用同一 traceId 检索。

> 第 8 卷用 Spring Cloud Sleuth/Micrometer Tracing 后，这套手工逻辑由框架自动完成，本卷先理解原理。

---

## 5. Logback JSON 与按天滚动

路径：`src/main/resources/logback-spring.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <springProperty scope="context" name="appName" source="spring.application.name"/>

    <!-- 控制台：带 traceId -->
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%X{traceId:-}] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>

    <!-- 按天滚动 + 保留30天 -->
    <appender name="FILE" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>logs/mars-platform.log</file>
        <rollingPolicy class="ch.qos.logback.core.rolling.TimeBasedRollingPolicy">
            <fileNamePattern>logs/mars-platform.%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>100MB</maxFileSize>
            <maxHistory>30</maxHistory>
            <totalSizeCap>5GB</totalSizeCap>
        </rollingPolicy>
        <encoder class="net.logstash.logback.encoder.LogstashEncoder">
            <customFields>{"app":"${appName}"}</customFields>
        </encoder>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE"/>
    </root>
    <logger name="com.mars.concert" level="DEBUG"/>
</configuration>
```

JSON 编码器需要依赖（便于 ELK/Loki 结构化采集）：
```xml
<dependency>
  <groupId>net.logstash.logback</groupId>
  <artifactId>logstash-logback-encoder</artifactId>
  <version>7.4</version>
</dependency>
```
不想引依赖就把 FILE 的 encoder 也用普通 `<pattern>`（含 `%X{traceId}`）。

---

## 6. 自研 Starter 原理（自动装配机制）

Spring Boot 3 自动装配的关键：
1. 一个 starter 本质是「一个普通 jar + 一个自动配置类」。
2. 该类用 `@AutoConfiguration` + `@ConditionalOnXxx` 声明「在什么条件下装配哪些 Bean」。
3. 通过文件注册：
   `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
   每行写一个自动配置类全限定名。（Spring Boot 2 是 `spring.factories`，3 已改为 imports 文件。）
4. 配置属性用 `@ConfigurationProperties` + `@EnableConfigurationProperties` 绑定。

为什么需要 `@Conditional`：让 starter「聪明地」按使用者环境决定是否生效——没引入 Redis 就不配、已有人配了就不覆盖。

常用条件注解：
- `@ConditionalOnClass(Xxx.class)`：classpath 有某类才生效
- `@ConditionalOnMissingBean`：容器里没有该 Bean 才配（允许用户覆盖）
- `@ConditionalOnProperty(prefix,name,havingValue)`：配置开关
- `@ConditionalOnWebApplication`：Web 环境才生效

---

## 7. 实战：开发 mars-lock-starter（@DistributedLock）

### 7.1 新建独立模块/工程（可放一个独立目录 `mars-starters/mars-lock-starter`）
`pom.xml` 依赖设为 optional/provided（不强制打进使用方）：
```xml
<dependencies>
  <dependency>
    <groupId>org.redisson</groupId>
    <artifactId>redisson</artifactId>
    <version>3.34.1</version>
    <optional>true</optional>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-autoconfigure</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-aop</artifactId>
  </dependency>
</dependencies>
```

### 7.2 属性类
`com.mars.lock.LockProperties.java`
```java
@Data
@ConfigurationProperties(prefix = "mars.lock")
public class LockProperties {
    private String keyPrefix = "mars:lock:";
    private long waitTime = 3;
    private long leaseTime = 30;   // 秒；-1 时用看门狗
}
```

### 7.3 注解
`com.mars.lock.DistributedLock.java`
```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DistributedLock {
    String key();                      // SpEL
    long waitTime() default -1;        // -1 用默认
    long leaseTime() default -1;       // -1 用看门狗
}
```

### 7.4 切面
`com.mars.lock.DistributedLockAspect.java`
```java
@Aspect
public class DistributedLockAspect {
    private final RedissonClient redisson;
    private final LockProperties props;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    public DistributedLockAspect(RedissonClient r, LockProperties p){ this.redisson=r; this.props=p; }

    @Around("@annotation(dLock)")
    public Object around(ProceedingJoinPoint pjp, DistributedLock dLock) throws Throwable {
        long wait = dLock.waitTime() < 0 ? props.getWaitTime() : dLock.waitTime();
        long lease = dLock.leaseTime() < 0 ? props.getLeaseTime() : dLock.leaseTime();

        String key = props.getKeyPrefix() + parseKey(dLock.key(), pjp);
        RLock lock = redisson.getLock(key);
        boolean ok;
        try {
            ok = lease < 0
                ? lock.tryLock(wait, TimeUnit.SECONDS)          // 看门狗自动续期
                : lock.tryLock(wait, lease, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("加锁中断", e);
        }
        if (!ok) throw new RuntimeException("操作太频繁，请稍后重试");
        try {
            return pjp.proceed();
        } finally {
            if (lock.isHeldByCurrentThread()) lock.unlock();
        }
    }

    private String parseKey(String spel, ProceedingJoinPoint pjp) {
        if (!spel.contains("#")) return spel;
        MethodSignature ms = (MethodSignature) pjp.getSignature();
        StandardEvaluationContext ctx = new StandardEvaluationContext();
        String[] names = ms.getParameterNames();
        Object[] args = pjp.getArgs();
        for (int i=0;i<names.length;i++) ctx.setVariable(names[i], args[i]);
        Object v = parser.parseExpression(spel).getValue(ctx);
        return v == null ? spel : v.toString();
    }
}
```

### 7.5 自动配置类
`com.mars.lock.MarsLockAutoConfiguration.java`
```java
@AutoConfiguration
@ConditionalOnClass(RedissonClient.class)
@ConditionalOnProperty(prefix = "mars.lock", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(LockProperties.class)
public class MarsLockAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(RedissonClient.class)
    public DistributedLockAspect distributedLockAspect(RedissonClient r, LockProperties p) {
        return new DistributedLockAspect(r, p);
    }
}
```

### 7.6 注册
`src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
```
com.mars.lock.MarsLockAutoConfiguration
```

### 7.7 使用方
业务工程引入该 starter 依赖后，任意方法：
```java
@DistributedLock(key = "'seckill:' + #actId + ':' + #userId")
public String execute(Long userId, Long actId) { ... }
```
即获得分布式锁能力，无侵入。

> `mvn install` 该 starter 到本地仓库后，主工程 pom 用 GAV 引入即可。

---

## 8. 实战：开发 mars-log-starter（@OperationLog 通用化）

把第 6 卷的操作日志 AOP 也做成 starter，结构同上：
- `@OperationLog` 注解、`OperationLogProperties`（开关、异步线程数）、`OperationLogAutoConfiguration`；
- 差异：落库方式不能写死。定义接口 `OperationLogHandler { void handle(OperationLogData data); }`，starter 提供默认的「只打印日志」Bean；使用方实现该接口写自己的表（`@ConditionalOnMissingBean` 让使用方实现优先）。
- 这样 starter 不依赖具体业务表，通用性强。

Handler 接口：
```java
public interface OperationLogHandler {
    void handle(OperationLogData data);
}
```
默认实现：
```java
@ConditionalOnMissingBean(OperationLogHandler.class)
@Bean
public OperationLogHandler defaultHandler() {
    return data -> LoggerFactory.getLogger("OPERATION_LOG").info(JSON.toJSONString(data));
}
```
使用方：
```java
@Component
@RequiredArgsConstructor
public class DbOperationLogHandler implements OperationLogHandler {
    private final SysOperationLogMapper mapper;
    @Override public void handle(OperationLogData d) { mapper.insert(convert(d)); }
}
```

完成 mars-lock-starter、mars-log-starter 两个后，你就彻底理解了「为什么引一个 starter 依赖，功能就自动生效」。

---

## 9. 单模块重构为多 Maven 模块

### 9.1 目标结构
```
mars-platform/                     父 pom (packaging=pom)
├── pom.xml
├── mars-common/                   工具/结果/异常/常量
├── mars-model/                    Entity/DTO/VO/Query/事件
├── mars-dao/                      Mapper + XML（依赖 model）
├── mars-service/                  业务逻辑（依赖 dao、model）
├── mars-component/
│   ├── mars-lock-starter/
│   └── mars-log-starter/
└── mars-bootstrap/                启动模块（application.yml、static、main、Controller 也可先放这）
```

### 9.2 父 pom
```xml
<groupId>com.mars</groupId>
<artifactId>mars-platform-parent</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>
<modules>
  <module>mars-common</module>
  <module>mars-model</module>
  <module>mars-dao</module>
  <module>mars-service</module>
  <module>mars-bootstrap</module>
</modules>
<!-- dependencyManagement 统一锁版本；spring-boot-starter-parent 仍作为 parent -->
```

### 9.3 子模块 pom 示例（mars-dao）
```xml
<parent>mars-platform-parent 1.0.0</parent>
<artifactId>mars-dao</artifactId>
<dependencies>
  <dependency><groupId>com.mars</groupId><artifactId>mars-model</artifactId><version>1.0.0</version></dependency>
  <dependency>com.baomidou:mybatis-plus-spring-boot3-starter</dependency>
  <dependency>com.mysql:mysql-connector-j</dependency>
</dependencies>
```

### 9.4 迁移步骤（降低风险）
1. 先建模块骨架，父 pom modules 暂不全开；
2. 按「model → common → dao → service → bootstrap」顺序搬包，每搬一层 `mvn -q -pl 模块 compile` 验证；
3. 启动类、yml、static 放 bootstrap；`@MapperScan("com.mars.concert.**.mapper")`、`@SpringBootApplication(scanBasePackages="com.mars")` 保证能扫到其它模块的 Bean；
4. Mapper XML 若用了，bootstrap 的 yml 加 `mybatis-plus.mapper-locations: classpath*:mapper/**/*.xml`；
5. 全量 `mvn clean package`，`java -jar mars-bootstrap/target/mars-bootstrap-1.0.0.jar` 验证。

### 9.5 为什么这样分
- 依赖方向单向（bootstrap → service → dao → model/common），无循环依赖；
- 第 8 卷拆微服务时，`service` 里按域的包（member/show/order…）可整块搬走，`model/api` 作为服务间契约模块。

---

## 10. JUnit5 + Mockito 单元测试

依赖（starter-test 已含 JUnit5、Mockito、AssertJ）。

### 10.1 纯单元测试（不启 Spring，最快）
路径：`test/.../order/PriceEngineTest.java`
```java
class PriceEngineTest {
    private final PriceEngine engine = new PriceEngine();

    @Test
    void should_calculate_total_and_service_fee() {
        List<PriceLine> lines = List.of(new PriceLine(1L, new BigDecimal("380.00"), 2));
        OrderPrice p = engine.calculate(lines);
        assertThat(p.getTotalAmount()).isEqualByComparingTo("760.00");
        assertThat(p.getServiceAmount()).isEqualByComparingTo("20.00"); // 2张×10
        assertThat(p.getPayAmount()).isEqualByComparingTo("780.00");
    }

    @Test
    void should_reject_non_positive_quantity() {
        assertThatThrownBy(() -> engine.calculate(List.of(new PriceLine(1L, new BigDecimal("10"), 0))))
            .isInstanceOf(BusinessException.class);
    }
}
```

### 10.2 Mockito：隔离 Mapper 测 Service
```java
@ExtendWith(MockitoExtension.class)
class OrderServiceImplTest {

    @Mock EventTierMapper eventTierMapper;
    @Mock OrderMapper orderMapper;
    @InjectMocks OrderServiceImpl orderService;

    @Test
    void should_throw_when_stock_not_enough() {
        EventTier et = new EventTier();
        et.setId(10L); et.setPrice(new BigDecimal("380"));
        when(eventTierMapper.selectOne(any())).thenReturn(et);
        when(eventTierMapper.deductStock(anyLong(), anyInt())).thenReturn(0); // 库存不足
        CreateOrderDTO dto = new CreateOrderDTO();
        dto.setEventId(1L); dto.setIdempotentKey("k1");
        dto.setItems(List.of(new OrderItemDTO(1L,1,null)));

        assertThatThrownBy(() -> orderService.createOrder(99L, dto))
            .hasMessageContaining("余票不足");
    }
}
```
要点：`@Mock` 造假依赖、`when().thenReturn()` 打桩、`verify(mapper).xxx()` 验证交互；AssertJ 的 `assertThat` 链式断言更易读。

---

## 11. Testcontainers 集成测试

单测 Mock 了数据库，集成测试用**一次性真实容器**验证 SQL/事务/Redis/MQ 真实行为。

### 11.1 依赖
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-testcontainers</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>mysql</artifactId>
  <scope>test</scope>
</dependency>
```

### 11.2 测试基类（自动起 MySQL 容器）
```java
@SpringBootTest
@Testcontainers
abstract class BaseIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("mars_test")
            .withUsername("test").withPassword("test")
            .withInitScript("schema.sql");   // test/resources 下放建表脚本

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mysql::getJdbcUrl);
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
    }
}
```

### 11.3 下单防超卖集成测试
```java
class OrderConcurrencyTest extends BaseIntegrationTest {
    @Autowired OrderService orderService;
    @Autowired EventTierMapper eventTierMapper;

    @Test
    void should_not_oversell_under_concurrency() throws Exception {
        // 某票档库存1（schema.sql 或这里构造）
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger success = new AtomicInteger();
        List<Future<?>> fs = new ArrayList<>();
        for (int i=0;i<threads;i++){
            long uid = 1000+i;
            fs.add(pool.submit(() -> {
                try {
                    CreateOrderDTO dto = new CreateOrderDTO();
                    dto.setEventId(1L); dto.setIdempotentKey(UUID.randomUUID().toString());
                    dto.setItems(List.of(new OrderItemDTO(1L,1,null)));
                    orderService.createOrder(uid, dto);
                    success.incrementAndGet();
                } catch (Exception ignored) {}
            }));
        }
        for (Future<?> f:fs) f.get();
        assertThat(success.get()).isEqualTo(1);   // 只有1单成功
        EventTier et = eventTierMapper.selectById(1L);
        assertThat(et.getSoldStock()).isEqualTo(1);
    }
}
```

> 首次跑 Testcontainers 会拉镜像，较慢；之后复用。它能验证「条件更新 SQL 在真实 InnoDB 下确实不超卖」，是最有说服力的测试。Redis/RabbitMQ 也有对应容器（`GenericRedisContainer`、`RabbitMQContainer`）。

---

## 12. 验收 Checklist

- [ ] `/actuator/health` UP，`/actuator/prometheus` 含 JVM 与 `mars_order_*` 指标
- [ ] Grafana 看到 QPS/RT/JVM/下单成功/库存不足面板
- [ ] 每条日志带 traceId；响应头含 X-Trace-Id
- [ ] 异步线程、MQ 消费日志也能串同一条 traceId
- [ ] 日志按天滚动、JSON 结构化
- [ ] `mars-lock-starter` 安装后，主工程仅加 `@DistributedLock` 即生效（不引 starter 不报错）
- [ ] `mars-log-starter` 默认打印；使用方自定义 Handler 后自动切换落库
- [ ] 多模块 `mvn clean package` 成功，fat jar 可启动并访问页面/接口
- [ ] PriceEngine 单测、OrderService Mockito 单测通过
- [ ] Testcontainers 并发防超卖集成测试通过（20 线程仅 1 单成功）

---

## 下一卷预告（第 8 卷：Spring Cloud 微服务）
把单体按域拆成 auth/show/inventory/order/pay/content/… 多服务；Nacos 注册与配置中心、Spring Cloud Gateway 统一入口与鉴权下推、OpenFeign + LoadBalancer 服务调用、Sentinel 限流熔断、Seata 处理下单-扣库存-积分分布式事务、Micrometer Tracing 全链路追踪、多环境与 Docker Compose 编排。
