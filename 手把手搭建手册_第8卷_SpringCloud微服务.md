# 手把手搭建手册（第 8 卷）
## Spring Cloud 微服务化：Nacos、Gateway、OpenFeign、Sentinel、Seata、链路追踪

> 前置：第 1~7 卷（单体功能完整、已按域分包/分模块）。本卷把单体**按业务域拆成多个可独立部署的服务**，引入服务注册发现、统一网关、声明式调用、限流熔断和分布式事务。
> 学习方法：**先拆 2~3 个服务跑通全套治理组件，再逐步拆全**，不要一次性拆十几个。建议先拆 `auth-service`、`show-service`、`order-service` 三个，把注册/网关/Feign/事务跑通。

---

## 目录
1. [微服务全景与拆分规划](#1-微服务全景与拆分规划)
2. [版本兼容矩阵（务必严格对应）](#2-版本兼容矩阵务必严格对应)
3. [启动 Nacos 与工程父 POM](#3-启动-nacos-与工程父-pom)
4. [第一个服务：show-service 注册到 Nacos](#4-第一个服务show-service-注册到-nacos)
5. [Nacos 配置中心与动态刷新](#5-nacos-配置中心与动态刷新)
6. [Spring Cloud Gateway 统一入口](#6-spring-cloud-gateway-统一入口)
7. [网关全局 JWT 鉴权过滤器（鉴权下推）](#7-网关全局-jwt-鉴权过滤器鉴权下推)
8. [网关限流与跨域](#8-网关限流与跨域)
9. [OpenFeign 服务调用 + 负载均衡 + fallback](#9-openfeign-服务调用--负载均衡--fallback)
10. [请求头透传（traceId/userId）](#10-请求头透传traceiduserid)
11. [Sentinel 限流熔断](#11-sentinel-限流熔断)
12. [Seata 分布式事务（AT 模式）](#12-seata-分布式事务at-模式)
13. [全链路追踪 Micrometer Tracing](#13-全链路追踪-micrometer-tracing)
14. [docker-compose 编排与启动顺序](#14-docker-compose-编排与启动顺序)
15. [拆分实施步骤与排错](#15-拆分实施步骤与排错)

---

## 1. 微服务全景与拆分规划

```
                         Nacos（注册中心 + 配置中心）
                                  ▲ 注册/拉配置
浏览器/前端 ──> Spring Cloud Gateway (8080)  ──路由/鉴权/限流/跨域
                    │
   ┌────────┬───────┼──────────┬───────────┬────────────┐
 auth-svc  show-svc  order-svc  pay-svc  content-svc ...（各自独立端口/进程/库）
   9001      9002      9003      9004       9005
            ▲ Feign 互相调用（注册发现 + 负载均衡）
   MySQL（可按服务分库） Redis  RabbitMQ  Seata Server(TC)
```

服务与第 7 卷模块/包对应：
| 服务 | 来源包 | 端口 | 主要表/职责 |
|---|---|---|---|
| mars-gateway | 新建 | 8080 | 路由、统一鉴权、限流、CORS |
| auth-service | member + security | 9001 | 登录/JWT/RBAC |
| show-service | show + inventory | 9002 | 场次/票档/座位库存 |
| order-service | order | 9003 | 购物车/订单/状态机 |
| pay-service | payment + ticket | 9004 | 支付/退款/出票/核销 |
| content-service | content | 9005 | FAQ/歌单/艺人 |
| promotion-service | promotion | 9006 | 券/秒杀/积分 |
| notify-service | notify | 9007 | 消息中心 |
| ai-service | （第9卷） | 9009 | AI 客服 |

> 先物理拆，数据库本卷可先共用一个 `mars_concert`（用表前缀区分）；理解治理后再按服务**拆库**（拆分时做数据迁移）。

---

## 2. 版本兼容矩阵（务必严格对应）

Spring Cloud 版本与 Boot 强绑定，乱配必报错：

| Spring Boot | Spring Cloud | Spring Cloud Alibaba | JDK |
|---|---|---|---|
| 3.3.x | 2023.0.x（Leyton） | 2023.0.1.x | 17 |

本卷固定：Boot 3.3.5 + Cloud 2023.0.3 + Alibaba 2023.0.1.3。Nacos Server 用 2.3.x（控制台 8848）。

---

## 3. 启动 Nacos 与工程父 POM

### 3.1 启动 Nacos
```powershell
docker run -d --name mars-nacos -p 8848:8848 -p 9848:9848 -p 9849:9849 ^
 -e MODE=standalone ^
 -e NACOS_AUTH_ENABLE=false ^
 nacos/nacos-server:v2.3.2
```
控制台 http://localhost:8848/nacos （默认 nacos/nacos）。Nacos2.x 除 8848 还要开放 9848（gRPC），Docker 已映射。

### 3.2 新建多服务父工程 `mars-cloud`
`pom.xml`：
```xml
<groupId>com.mars</groupId>
<artifactId>mars-cloud-parent</artifactId>
<version>1.0.0</version>
<packaging>pom</packaging>

<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.3.5</version>
</parent>

<properties>
  <java.version>17</java.version>
  <spring-cloud.version>2023.0.3</spring-cloud.version>
  <spring-cloud-alibaba.version>2023.0.1.3</spring-cloud-alibaba.version>
</properties>

<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.cloud</groupId>
      <artifactId>spring-cloud-dependencies</artifactId>
      <version>${spring-cloud.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <dependency>
      <groupId>com.alibaba.cloud</groupId>
      <artifactId>spring-cloud-alibaba-dependencies</artifactId>
      <version>${spring-cloud-alibaba.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <!-- 内部 api 模块统一版本 -->
    <dependency><groupId>com.mars</groupId><artifactId>mars-api</artifactId><version>1.0.0</version></dependency>
    <dependency><groupId>com.mars</groupId><artifactId>mars-common</artifactId><version>1.0.0</version></dependency>
  </dependencies>
</dependencyManagement>

<modules>
  <module>mars-common</module>
  <module>mars-api</module>
  <module>mars-gateway</module>
  <module>mars-auth-service</module>
  <module>mars-show-service</module>
  <module>mars-order-service</module>
</modules>
```

- `mars-common`：Result/异常/常量/DTO/工具（从第 7 卷搬）。
- `mars-api`：**服务间契约**——放 Feign 接口、请求/响应 DTO、MQ 事件。供调用方依赖。

各业务服务最小依赖（以 show-service 为例）：
```xml
<dependencies>
  <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
  <dependency>com.mars:mars-common</dependency>
  <dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId>
  </dependency>
  <dependency>
    <groupId>com.alibaba.cloud</groupId>
    <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
  </dependency>
  <dependency>
    <groupId>org.springframework.cloud</groupId>
    <artifactId>spring-cloud-starter-bootstrap</artifactId>
  </dependency>
  <!-- mybatis-plus / mysql / redis 等同前几卷 -->
</dependencies>
```

---

## 4. 第一个服务：show-service 注册到 Nacos

### 4.1 bootstrap.yml（引导配置，Nacos 配置中心需要在应用启动早期读取）
`show-service/src/main/resources/bootstrap.yml`
```yaml
spring:
  application:
    name: mars-show-service
  profiles:
    active: dev
  cloud:
    nacos:
      server-addr: localhost:8848
      discovery:
        namespace: public
        group: MARS_GROUP
      config:
        server-addr: localhost:8848
        file-extension: yaml
        group: MARS_GROUP
        # 共享配置：数据库/redis 等所有服务通用
        shared-configs:
          - data-id: mars-common-dev.yaml
            group: MARS_GROUP
            refresh: true
```

### 4.2 application.yml
```yaml
server:
  port: 9002
```
数据库/redis 放到 Nacos 的共享配置 `mars-common-dev.yaml`。

### 4.3 启动类
```java
@SpringBootApplication
@EnableDiscoveryClient
@MapperScan("com.mars.show.mapper")
public class ShowServiceApplication {
    public static void main(String[] args) { SpringApplication.run(ShowServiceApplication.class,args); }
}
```

### 4.4 验证
启动后 Nacos 控制台「服务管理 → 服务列表」出现 `mars-show-service`，实例 9002 健康。
> 服务名（spring.application.name）就是 Feign/网关路由使用的逻辑名。

---

## 5. Nacos 配置中心与动态刷新

在 Nacos 控制台「配置管理 → 配置列表」，Group 选 `MARS_GROUP`，新建：
- Data ID `mars-common-dev.yaml`：放 datasource、redis、rabbitmq、jwt 等公共配置；
- Data ID `mars-show-service-dev.yaml`：放该服务私有配置（如座位锁 TTL）。

动态刷新：配置类加 `@RefreshScope`，在 Nacos 改配置发布后 Bean 自动更新：
```java
@RestController
@RefreshScope
public class SeatConfigController {
    @Value("${mars.seat.lock-ttl:300}") private int lockTtl;
    @GetMapping("/seat/lock-ttl") public int ttl(){ return lockTtl; }
}
```

命名空间用法：`dev/test/prod` 各建 namespace（有独立 ID），bootstrap.yml `discovery.namespace`/`config.namespace` 填对应 ID，实现环境隔离。

---

## 6. Spring Cloud Gateway 统一入口

Gateway 基于 WebFlux（**不要在网关引入 spring-boot-starter-web**，会冲突）。

### 6.1 依赖
```xml
<dependency><groupId>org.springframework.cloud</groupId>
  <artifactId>spring-cloud-starter-gateway</artifactId></dependency>
<dependency><groupId>com.alibaba.cloud</groupId>
  <artifactId>spring-cloud-starter-alibaba-nacos-discovery</artifactId></dependency>
<dependency><groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis-reactive</artifactId></dependency>
```

### 6.2 bootstrap.yml / application.yml
```yaml
server:
  port: 8080
spring:
  application:
    name: mars-gateway
  cloud:
    nacos: { server-addr: localhost:8848, discovery: {group: MARS_GROUP} }
    gateway:
      discovery:
        locator:
          enabled: false
      routes:
        - id: auth-service
          uri: lb://mars-auth-service
          predicates:
            - Path=/api/v1/auth/**
        - id: show-service
          uri: lb://mars-show-service
          predicates:
            - Path=/api/v1/events/**,/api/v1/show/**
        - id: order-service
          uri: lb://mars-order-service
          predicates:
            - Path=/api/v1/orders/**,/api/v1/cart/**
        - id: content-service
          uri: lb://mars-content-service
          predicates:
            - Path=/api/v1/faqs/**,/api/v1/content/**
```

> `lb://服务名` 表示从 Nacos 找实例并通过 LoadBalancer 负载均衡。前端只跟网关 8080 通信，不感知内部服务端口。静态站本卷可仍由网关/某服务托管（也可 Nginx 单独托管）。

---

## 7. 网关全局 JWT 鉴权过滤器（鉴权下推）

把第 2 卷的 token 校验收口到网关，内部服务默认信任网关透传的用户头。

路径：`mars-gateway/.../AuthGlobalFilter.java`
```java
@Component
@RequiredArgsConstructor
public class AuthGlobalFilter implements GlobalFilter, Ordered {

    private final JwtUtil jwtUtil;   // 可放到 gateway 自己的轻量工具，或复制密钥解析
    private final ReactiveStringRedisTemplate redis;

    private static final List<String> WHITE = List.of(
        "/api/v1/auth/", "/api/v1/faqs", "/doc.html", "/v3/api-docs",
        "/api/v1/events/home"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        String path = req.getURI().getPath();

        // 白名单（GET 公开查询也可在此细化）
        if (WHITE.stream().anyMatch(path::startsWith)
            || (req.getMethod() == HttpMethod.GET && path.startsWith("/api/v1/events"))) {
            return chain.filter(exchange);
        }
        if (!path.startsWith("/api/")) return chain.filter(exchange);  // 静态资源放行

        String token = req.getHeaders().getFirst("Authorization");
        if (token == null || !token.startsWith("Bearer ")) {
            return unauthorized(exchange, "未登录");
        }
        try {
            Claims c = jwtUtil.parse(token.substring(7));
            Long uid = Long.valueOf(c.getSubject());
            String device = c.get("device", String.class);
            String key = "mars:token:access:" + uid + ":" + device;
            return redis.opsForValue().get(key).defaultIfEmpty("").flatMap(jti -> {
                if (!c.getId().equals(jti)) return unauthorized(exchange, "登录已失效");
                // 透传用户身份给下游
                ServerHttpRequest mutated = req.mutate()
                    .header("X-User-Id", String.valueOf(uid))
                    .header("X-Username", c.getSubject())
                    .header("X-Trace-Id", traceId())
                    .build();
                return chain.filter(exchange.mutate().request(mutated).build());
            });
        } catch (Exception e) {
            return unauthorized(exchange, "token无效或已过期");
        }
    }

    private Mono<Void> unauthorized(ServerWebExchange ex, String msg) {
        ServerHttpResponse resp = ex.getResponse();
        resp.setStatusCode(HttpStatus.UNAUTHORIZED);
        resp.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"code\":1401,\"message\":\"" + msg + "\",\"data\":null}").getBytes());
        DataBuffer buffer = resp.bufferFactory().wrap(body);
        return resp.writeWith(Mono.just(buffer));
    }

    private String traceId(){ return UUID.randomUUID().toString().replace("-",""); }

    @Override public int getOrder(){ return -100; }
}
```

下游服务改造：不再各自跑 JWT 过滤器（可选保留作纵深防御），提供一个 WebFlux/Servlet 拦截器读取 `X-User-Id` 头填充 UserContext（内部服务只允许被网关访问，网络层隔离/或加内部头校验）。`@CurrentUser` 从 UserContext 取值逻辑不变，业务代码几乎零改动。

---

## 8. 网关限流与跨域

### 8.1 网关 CORS（统一在网关处理，下游不再配）
```java
@Bean
public CorsWebFilter corsWebFilter() {
    CorsConfiguration cfg = new CorsConfiguration();
    cfg.addAllowedOriginPattern("*");
    cfg.setAllowedMethods(List.of("GET","POST","PUT","DELETE","OPTIONS"));
    cfg.addAllowedHeader("*");
    cfg.addAllowedHeader("Authorization");
    cfg.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource(new PathPatternParser());
    source.registerCorsConfiguration("/**", cfg);
    return new CorsWebFilter(source);
}
```

### 8.2 RequestRateLimiter（Redis 令牌桶）
在路由加过滤器：
```yaml
- id: order-service
  uri: lb://mars-order-service
  predicates: [ Path=/api/v1/orders/** ]
  filters:
    - name: RequestRateLimiter
      args:
        redis-rate-limiter.replenishRate: 20    # 每秒补充20
        redis-rate-limiter.burstCapacity: 40    # 桶容量40
        key-resolver: "#{@ipKeyResolver}"       # 按IP限流
```
```java
@Bean
public KeyResolver ipKeyResolver() {
    return exchange -> Mono.just(
        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress());
}
```
返回 429 表示被限流。更精细的流控/熔断用 Sentinel（第 11 节）。

---

## 9. OpenFeign 服务调用 + 负载均衡 + fallback

### 9.1 在 mars-api 定义契约
```java
// mars-api 模块，调用方和提供方都依赖
@FeignClient(name = "mars-show-service", path = "/internal/show",
             fallbackFactory = ShowClientFallback.class)
public interface ShowClient {

    @GetMapping("/events/{id}/tiers/{tierId}")
    Result<TierInfoDTO> getTierInfo(@PathVariable("id") Long eventId,
                                    @PathVariable("tierId") Long tierId);

    @PostMapping("/inventory/deduct")
    Result<Boolean> deductStock(@RequestBody DeductStockDTO dto);

    @PostMapping("/inventory/release")
    Result<Void> releaseStock(@RequestBody ReleaseStockDTO dto);
}
```
> `/internal/**` 是服务间内部接口，网关不对外暴露（路由里不配置，或加内部鉴权）。

### 9.2 提供方实现内部接口
show-service 写一个普通 Controller 实现这些路径，复用它自己的 Service。

### 9.3 调用方
order-service 启动类加 `@EnableFeignClients(basePackages="com.mars.api")`，业务里直接注入：
```java
@Service
@RequiredArgsConstructor
public class OrderFacade {
    private final ShowClient showClient;

    public void create(...) {
        Result<TierInfoDTO> r = showClient.getTierInfo(eventId, tierId);
        if (r.getCode() != 0 || r.getData()==null)
            throw new BusinessException(OrderErrorCode.TIER_NOT_ON_SALE);
        // 用 r.getData().getPrice() 服务端算价
    }
}
```

### 9.4 fallback（降级）
```java
@Component
public class ShowClientFallback implements FallbackFactory<ShowClient> {
    @Override
    public ShowClient create(Throwable cause) {
        return new ShowClient() {
            @Override public Result<TierInfoDTO> getTierInfo(Long e, Long t) {
                throw new BusinessException(5001,"演出服务暂不可用，请稍后重试");
            }
            @Override public Result<Boolean> deductStock(DeductStockDTO d) {
                throw new BusinessException(5002,"库存服务繁忙，下单失败");
            }
            @Override public Result<Void> releaseStock(ReleaseStockDTO d) { return Result.success(); }
        };
    }
}
```
show-service 宕机时调用走 fallback，给用户友好提示而不是 500；非核心链路（如查推荐）fallback 可返回缓存/空数据。

> 负载均衡：order-service 启动两个实例（9002、9012）都注册 Nacos，Feign 调用默认轮询；在 Nacos 服务详情可见多实例。

---

## 10. 请求头透传（traceId/userId）

Feign 调用默认不带原请求头，需要拦截器透传：
```java
@Component
public class FeignHeaderInterceptor implements RequestInterceptor {
    @Override
    public void apply(RequestTemplate template) {
        ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        if (attrs != null) {
            HttpServletRequest req = attrs.getRequest();
            ofNullable(req.getHeader("X-Trace-Id")).ifPresent(v -> template.header("X-Trace-Id", v));
            ofNullable(req.getHeader("X-User-Id")).ifPresent(v -> template.header("X-User-Id", v));
        }
    }
}
```
下游用一个 Filter 把 `X-Trace-Id` 放 MDC、`X-User-Id` 放 UserContext。这样跨服务日志一条 traceId 串到底。

> 异步/Feign 脱离请求线程时 RequestContextHolder 为空，需用 Hystrix/Reactor Context 或显式传参，进阶场景可用 Micrometer Tracing 自动透传（第 13 节）。

---

## 11. Sentinel 限流熔断

### 11.1 启动控制台
```powershell
docker run -d --name mars-sentinel -p 8858:8858 ^
 bladex/sentinel-dashboard:1.8.8
```
默认 sentinel/sentinel。

### 11.2 依赖与配置
```xml
<dependency>
  <groupId>com.alibaba.cloud</groupId>
  <artifactId>spring-cloud-starter-alibaba-sentinel</artifactId>
</dependency>
```
```yaml
spring:
  cloud:
    sentinel:
      transport:
        dashboard: localhost:8858
      eager: true
```

### 11.3 使用
- **接口流控**：在控制台「簇点链路」对 `/orders` 等设 QPS 阈值；超阈值走 BlockException，返回 429/降级 JSON（实现 `BlockExceptionHandler`）。
- **Feign 熔断**：开启 `feign.sentinel.enabled=true`，用 Sentinel 替换/配合 fallback，按资源 RT/异常比例自动熔断。
- **热点参数限流**：对 `deductStock` 方法按 `eventId` 热点参数限流，防止单场过热打挂库存服务。
- **系统自适应**：按 Load/CPU/入口 QPS 系统级保护。

规则也可用 Nacos 数据源持久化（控制台规则默认内存存储，重启丢失）：
```yaml
spring.cloud.sentinel.datasource.ds.nacos.server-addr: localhost:8848
spring.cloud.sentinel.datasource.ds.nacos.data-id: ${spring.application.name}-sentinel.json
spring.cloud.sentinel.datasource.ds.nacos.rule-type: flow
```

---

## 12. Seata 分布式事务（AT 模式）

场景：order-service 下订单（写自己的库）+ show-service 扣库存（写另一个库），跨服务跨库，要保证同成功同失败。

### 12.1 启动 Seata Server（TC）
```powershell
docker run -d --name mars-seata -p 8091:8091 -p 7091:7091 ^
 -e SEATA_IP=127.0.0.1 ^
 seataio/seata-server:2.0.0
```
学习期可用 file 配置模式；生产用 Nacos 作注册/配置中心。

### 12.2 每服务依赖与配置
```xml
<dependency>
  <groupId>com.alibaba.cloud</groupId>
  <artifactId>spring-cloud-starter-alibaba-seata</artifactId>
</dependency>
```
每个业务库执行 Seata 的 `undo_log` 建表脚本（AT 模式回滚用，官方提供），并在对应库各建一张。

```yaml
seata:
  application-id: ${spring.application.name}
  tx-service-group: mars_tx_group
  service:
    vgroup-mapping:
      mars_tx_group: default
  registry:
    type: nacos
    nacos: { server-addr: localhost:8848, group: SEATA_GROUP }
```

### 12.3 发起全局事务
order-service 的下单方法（跨服务调用方）：
```java
@GlobalTransactional(rollbackFor = Exception.class, name = "createOrder")
public String createOrder(Long userId, CreateOrderDTO dto) {
    // 1. 写本地订单（order库）
    Orders order = ...; orderMapper.insert(order);
    // 2. Feign 调 show-service 扣库存（show库）
    Result<Boolean> r = showClient.deductStock(new DeductStockDTO(eventId, items));
    if (r.getCode() != 0 || !Boolean.TRUE.equals(r.getData())) {
        throw new BusinessException(OrderErrorCode.STOCK_NOT_ENOUGH); // 触发全局回滚
    }
    // 3. 可再调 promotion-service 核销券/扣积分
    return order.getOrderNo();
}
```
show-service 的本地方法保持普通 `@Transactional`，Seata 通过 `undo_log` 自动生成前后镜像；全局异常时 TC 协调各分支回滚。

> AT 模式要点：基于 SQL 解析自动补偿，业务无侵入，适合标准 CRUD；隔离级别默认读未提交，必要时用 `@GlobalLock + select for update`。复杂非关系型补偿场景再学 TCC/Saga。先用「共用单库 + @Transactional」跑通，再切「分库 + @GlobalTransactional」对比，理解最深刻。

---

## 13. 全链路追踪 Micrometer Tracing

```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
  <groupId>io.zipkin.reporter2</groupId>
  <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```
起 Zipkin：`docker run -d -p 9411:9411 openzipkin/zipkin`。

每个服务 yml：
```yaml
management:
  tracing:
    sampling:
      probability: 1.0     # 学习期全采样
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```
一次「网关→order→show」调用后，在 http://localhost:9411 可看到跨服务调用链与每段耗时（traceId/spanId 自动生成，自动透传，取代第 7 卷手工 MDC）。日志 pattern 加 `%X{traceId}/%X{spanId}`。

---

## 14. docker-compose 编排与启动顺序

`docker/cloud/docker-compose.yml` 聚合依赖（节选）：
```yaml
services:
  mysql:  { image: mysql:8.0, ports: ["3306:3306"], environment: { MYSQL_ROOT_PASSWORD: root123 } }
  redis:  { image: redis:7, ports: ["6379:6379"] }
  rabbitmq: { image: rabbitmq:3.13-management, ports: ["5672:5672","15672:15672"] }
  nacos:  { image: nacos/nacos-server:v2.3.2, ports: ["8848:8848","9848:9848"], environment: { MODE: standalone } }
  seata:  { image: seataio/seata-server:2.0.0, ports: ["8091:8091"] }
  sentinel: { image: bladex/sentinel-dashboard:1.8.8, ports: ["8858:8858"] }
  zipkin: { image: openzipkin/zipkin, ports: ["9411:9411"] }
  gateway: { build: ../../mars-gateway, ports: ["8080:8080"], depends_on: [nacos] }
```
启动顺序：中间件（mysql/redis/rabbit/nacos/seata）→ 基础服务（auth/show）→ 业务服务（order/pay）→ gateway（其实网关注册即可随时起）。

---

## 15. 拆分实施步骤与排错

推荐步骤：
1. 建 mars-cloud 父工程 + mars-common/mars-api，把通用类和 Feign 契约搬入，`mvn install`。
2. 拆 show-service（查询为主，最简单），注册 Nacos，独立跑通它自己的接口。
3. 建 gateway，配 show-service 路由，通过网关访问成功。
4. 拆 auth-service，网关切 JWT 鉴权，透传 X-User-Id。
5. 拆 order-service，用 Feign 调 show-service，跑通「下单→远程扣库存」，加 fallback 与头透传。
6. 接 Sentinel（限流/降级）与 Zipkin（看链路）。
7. 分库后启用 Seata `@GlobalTransactional` 验证跨库回滚。
8. 其余服务（pay/content/promotion/notify/ai）按需逐个拆。

常见错误：
| 现象 | 原因/解决 |
|---|---|
| Nacos 连不上/注册失败 | Nacos2 需 9848；检查 server-addr、namespace/group |
| bootstrap.yml 不生效 | Boot3 要引 spring-cloud-starter-bootstrap，或改用 spring.config.import |
| 网关 503 no instances | 服务名大小写/分组不一致；服务没注册健康；`lb://` 需 loadbalancer 依赖 |
| Gateway 启动报 Netty/Servlet 冲突 | 网关不能引 spring-boot-starter-web，用 WebFlux |
| Feign 401/拿不到用户 | 内部调用没透传 X-User-Id/X-Trace-Id；加 Feign RequestInterceptor |
| Feign 调不到本地服务 | @EnableFeignClients 的 basePackages 没包含契约包 |
| fallback 不生效 | 没配 fallbackFactory 或熔断未开；异常必须能被包装（被全局异常吞掉也可能不进 fallback） |
| Seata 不回滚 | 没加 undo_log；@GlobalTransactional 没在跨服务最外层；异常被 catch；事务组映射错 |
| 配置改了不刷新 | Bean 要 @RefreshScope；dataId/group 与 bootstrap 一致 |
| 链路 traceId 断开 | 没用 micrometer 自动透传或 Feign 拦截器漏传 |
| 端口冲突 | 每个服务独立 server.port |

### Checklist
- [ ] 3 个服务均在 Nacos 注册健康
- [ ] 所有外部请求只经过网关 8080，内部端口不直接暴露
- [ ] 无 token 访问受保护接口被网关拦 401；带 token 透传用户成功
- [ ] order 通过 Feign 调 show 成功；关掉 show 后走 fallback 友好降级
- [ ] 多实例时 Feign 负载均衡生效
- [ ] Sentinel 对下单接口限流生效（超限 429）
- [ ] Zipkin 可见 网关→order→show 完整链路
- [ ] 分库后 Seata 模拟库存失败，订单与库存一起回滚（无脏数据）
- [ ] Nacos 改 @RefreshScope 配置实时生效

---

## 下一卷预告（第 9 卷：Spring AI 智能客服）
接入大模型（OpenAI 兼容/通义 DashScope）；`ChatClient` 与 SSE 流式；基于 FAQ/政策文档的 RAG 向量检索（EmbeddingModel + VectorStore）；Function Calling 让 AI 查订单/查余票/发起退款；ChatMemory 多轮对话与 Redis 存储；安全护栏与敏感操作二次确认。
