# 手把手搭建手册（第 5 卷）
## RabbitMQ 消息、支付回调幂等、电子票二维码与核销、消息中心

> 前置：第 1~4 卷。本卷把交易闭环走完：**发起支付 → 模拟支付网关 → 异步回调（验签+幂等）→ 支付成功消息扇出 → 出票（二维码）/加积分/发通知 → 扫码核销 → 退款**。
> 核心训练目标：可靠消息（不丢、不重复消费）、最终一致性、第三方回调的幂等处理。
> 新增包：`payment`（支付域）、`ticket`（票品域）、`notify`（消息域）、`common.mq`（MQ 基础设施）。

---

## 目录
1. [整体时序与最终一致性](#1-整体时序与最终一致性)
2. [启动 RabbitMQ 与依赖配置](#2-启动-rabbitmq-与依赖配置)
3. [消息拓扑（交换机/队列/死信）声明](#3-消息拓扑交换机队列死信声明)
4. [可靠投递：confirm/return/本地消息表](#4-可靠投递confirmreturn本地消息表)
5. [支付/票 建表 SQL](#5-支付票-建表-sql)
6. [支付 Service：发起支付](#6-支付-service发起支付)
7. [模拟支付网关与异步回调](#7-模拟支付网关与异步回调)
8. [回调幂等与支付成功主流程](#8-回调幂等与支付成功主流程)
9. [支付成功事件扇出（出票/积分/通知）](#9-支付成功事件扇出出票积分通知)
10. [消费者：手动 ACK、幂等、重试、死信](#10-消费者手动-ack幂等重试死信)
11. [电子票二维码（zxing）](#11-电子票二维码zxing)
12. [扫码核销（防重复）](#12-扫码核销防重复)
13. [退款流程](#13-退款流程)
14. [消息中心（模板/记录/邮件/短信mock）](#14-消息中心模板记录邮件短信mock)
15. [测试与排错](#15-测试与排错)

---

## 1. 整体时序与最终一致性

```
前端点"去支付" → POST /payments/create
   → 生成支付单(INIT) + 本地消息(待发送,同事务) → 返回收银台信息(模拟支付页URL)
用户在模拟支付页点"已支付" → 模拟网关 POST /pay/callback/mock（带签名）
   → 验签 → 以渠道流水号查支付单 → 幂等判断
   → 同事务：支付单SUCCESS + 订单WAIT_PAY→PAID(状态机) + 标记本地消息已发送
事务提交后 → MQ 发布 PaySuccessEvent
        ├─> 出票消费者：生成 tk_ticket + 二维码 token，订单 PAID→TICKETED
        ├─> 积分消费者：加积分（第6卷用，本卷先记日志）
        └─> 通知消费者：邮件/站内信/短信mock
   → 前端轮询 /payments/{orderNo}/status 或 WS 推送得到结果
```

为什么需要这套复杂度：
- 支付结果**由第三方异步通知**，不能靠前端跳转（用户可能关页面），必须服务端回调为准；
- 回调可能**重复发送**，必须幂等；
- 「改订单状态」和「发出下游消息」若分两步会有「改了但没发/发了但没改」的不一致 → 用**本地消息表**保证最终一致。

---

## 2. 启动 RabbitMQ 与依赖配置

```powershell
docker run -d --name mars-rabbit -p 5672:5672 -p 15672:15672 ^
  -e RABBITMQ_DEFAULT_USER=mars -e RABBITMQ_DEFAULT_PASS=mars123 ^
  rabbitmq:3.13-management
```
控制台：http://localhost:15672 （mars/mars123）。

`pom.xml` 加：
```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-amqp</artifactId>
</dependency>
<!-- 二维码 -->
<dependency>
  <groupId>com.google.zxing</groupId>
  <artifactId>core</artifactId>
  <version>3.5.3</version>
</dependency>
<dependency>
  <groupId>com.google.zxing</groupId>
  <artifactId>javase</artifactId>
  <version>3.5.3</version>
</dependency>
<!-- 邮件 -->
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-mail</artifactId>
</dependency>
```

`application.yml` 追加：
```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: mars
    password: mars123
    virtual-host: /
    publisher-confirm-type: correlated   # 发布到交换机的确认(异步回调)
    publisher-returns: true             # 交换机找不到队列时退回
    template:
      mandatory: true
    listener:
      simple:
        acknowledge-mode: manual       # 消费端手动ACK，处理完再确认
        prefetch: 10                    # 每次预取10条，控制单消费者速度
        retry:
          enabled: false                # 不用框架自动重试，自己用死信+补偿，语义更清晰
  mail:
    host: smtp.qq.com                   # 按需改成你的邮箱SMTP
    port: 587
    username: your_mail@qq.com
    password: your_smtp_authorization_code   # 邮箱的SMTP授权码(不是登录密码)
    properties:
      mail.smtp.starttls.enable: true

mars:
  pay:
    mock:
      # 模拟支付页/回调的基础地址
      base-url: http://localhost:8080
```

> 学习期邮件若没配 SMTP，把发送 try-catch 住只打印日志，不影响主流程（第 14 节给开关）。

---

## 3. 消息拓扑（交换机/队列/死信）声明

路径：`common/mq/MqConstants.java`

```java
package com.mars.concert.common.mq;

public interface MqConstants {
    String PAY_EXCHANGE = "pay.exchange";
    String RK_PAY_SUCCESS = "pay.success";

    String Q_TICKET_CREATE = "q.ticket.create";
    String Q_POINT_ADD = "q.point.add";
    String Q_NOTIFY_SEND = "q.notify.send";

    // 死信
    String DLX_EXCHANGE = "dlq.exchange";
    String Q_TICKET_CREATE_DLQ = "q.ticket.create.dlq";
    String Q_POINT_ADD_DLQ = "q.point.add.dlq";
    String Q_NOTIFY_SEND_DLQ = "q.notify.send.dlq";
}
```

路径：`common/mq/RabbitTopologyConfig.java`

```java
package com.mars.concert.common.mq;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 用 Bean 声明交换机/队列/绑定。应用启动时若不存在则自动创建。
 * 每个业务队列都绑定一个死信交换机：消费失败 nack(requeue=false) 后消息进死信队列。
 */
@Configuration
public class RabbitTopologyConfig {

    @Bean
    public TopicExchange payExchange() {
        return ExchangeBuilder.topicExchange(MqConstants.PAY_EXCHANGE).durable(true).build();
    }

    @Bean
    public DirectExchange dlxExchange() {
        return ExchangeBuilder.directExchange(MqConstants.DLX_EXCHANGE).durable(true).build();
    }

    @Bean
    public Queue ticketQueue() {
        return QueueBuilder.durable(MqConstants.Q_TICKET_CREATE)
                .withArgument("x-dead-letter-exchange", MqConstants.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.Q_TICKET_CREATE_DLQ)
                .build();
    }

    @Bean
    public Queue pointQueue() {
        return QueueBuilder.durable(MqConstants.Q_POINT_ADD)
                .withArgument("x-dead-letter-exchange", MqConstants.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.Q_POINT_ADD_DLQ)
                .build();
    }

    @Bean
    public Queue notifyQueue() {
        return QueueBuilder.durable(MqConstants.Q_NOTIFY_SEND)
                .withArgument("x-dead-letter-exchange", MqConstants.DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", MqConstants.Q_NOTIFY_SEND_DLQ)
                .build();
    }

    @Bean
    public Binding bindTicket() {
        return BindingBuilder.bind(ticketQueue()).to(payExchange()).with(MqConstants.RK_PAY_SUCCESS);
    }
    @Bean
    public Binding bindPoint() {
        return BindingBuilder.bind(pointQueue()).to(payExchange()).with(MqConstants.RK_PAY_SUCCESS);
    }
    @Bean
    public Binding bindNotify() {
        return BindingBuilder.bind(notifyQueue()).to(payExchange()).with(MqConstants.RK_PAY_SUCCESS);
    }

    /* ---- 死信队列本身 ---- */
    @Bean public Queue ticketDlq() { return QueueBuilder.durable(MqConstants.Q_TICKET_CREATE_DLQ).build(); }
    @Bean public Queue pointDlq() { return QueueBuilder.durable(MqConstants.Q_POINT_ADD_DLQ).build(); }
    @Bean public Queue notifyDlq() { return QueueBuilder.durable(MqConstants.Q_NOTIFY_SEND_DLQ).build(); }

    @Bean public Binding bindTicketDlq() {
        return BindingBuilder.bind(ticketDlq()).to(dlxExchange()).with(MqConstants.Q_TICKET_CREATE_DLQ);
    }
    @Bean public Binding bindPointDlq() {
        return BindingBuilder.bind(pointDlq()).to(dlxExchange()).with(MqConstants.Q_POINT_ADD_DLQ);
    }
    @Bean public Binding bindNotifyDlq() {
        return BindingBuilder.bind(notifyDlq()).to(dlxExchange()).with(MqConstants.Q_NOTIFY_SEND_DLQ);
    }
}
```

> 一个 routingKey `pay.success` 被三个队列绑定 → 一条消息三个消费者各收一份（**发布/订阅扇出**），正是支付成功后要同时出票、加积分、发通知所需。

---

## 4. 可靠投递：confirm/return/本地消息表

### 4.1 本地消息表
```sql
CREATE TABLE sys_local_message (
  id BIGINT NOT NULL AUTO_INCREMENT,
  biz_no VARCHAR(64) NOT NULL COMMENT '业务号(订单号)',
  exchange VARCHAR(64) NOT NULL,
  routing_key VARCHAR(64) NOT NULL,
  payload TEXT NOT NULL COMMENT '消息体JSON',
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENT',
  retry_count INT NOT NULL DEFAULT 0,
  next_retry DATETIME,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_status_next(status,next_retry)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='本地消息表';
```

### 4.2 发送器（confirm 回调更新状态 + 失败重试）
路径：`common/mq/ReliableMessageSender.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableMessageSender {

    private final RabbitTemplate rabbitTemplate;
    private final LocalMessageMapper localMessageMapper;

    /** 业务在事务内调用：先落库一条 PENDING（与业务数据同事务） */
    public void savePending(String bizNo, String exchange, String rk, Object payload) {
        LocalMessage m = new LocalMessage();
        m.setBizNo(bizNo); m.setExchange(exchange); m.setRoutingKey(rk);
        m.setPayload(JSON.toJSONString(payload));
        m.setStatus("PENDING");
        m.setNextRetry(LocalDateTime.now());
        localMessageMapper.insert(m);
    }

    /** 事务提交后调用发送；带 confirm 确认 broker 收到 */
    public void send(Long messageId) {
        LocalMessage m = localMessageMapper.selectById(messageId);
        if (m == null || "SENT".equals(m.getStatus())) return;

        CorrelationData cd = new CorrelationData(String.valueOf(m.getId()));
        cd.getFuture().whenComplete((result, ex) -> {
            if (ex != null || result == null || !result.isAck()) {
                log.error("消息投递未确认 id={}", messageId);   // 由补偿任务重试
            } else {
                LocalMessage up = new LocalMessage();
                up.setId(m.getId()); up.setStatus("SENT");
                localMessageMapper.updateById(up);
            }
        });
        rabbitTemplate.convertAndSend(m.getExchange(), m.getRoutingKey(), m.getPayload(), cd);
    }
}
```

### 4.3 消息补偿定时任务（保证不丢）
```java
@Component
@RequiredArgsConstructor
public class LocalMessageRetryJob {
    private final LocalMessageMapper mapper;
    private final ReliableMessageSender sender;

    @Scheduled(fixedDelay = 10_000)
    public void retry() {
        List<LocalMessage> pending = mapper.selectList(Wrappers.<LocalMessage>lambdaQuery()
            .eq(LocalMessage::getStatus, "PENDING")
            .lt(LocalMessage::getNextRetry, LocalDateTime.now())
            .lt(LocalMessage::getRetryCount, 8)
            .last("LIMIT 50"));
        for (LocalMessage m : pending) {
            try { sender.send(m.getId()); }
            catch (Exception e) {
                m.setRetryCount(m.getRetryCount()+1);
                m.setNextRetry(LocalDateTime.now().plusSeconds((long)Math.pow(2,m.getRetryCount()))); // 指数退避
                mapper.updateById(m);
            }
        }
    }
}
```

> return 回调（交换机找不到队列）：配置 `RabbitTemplate.setReturnsCallback` 记录告警日志即可（拓扑由我们声明，正常不会触发）。

---

## 5. 支付/票 建表 SQL

```sql
CREATE TABLE pay_payment (
  id BIGINT NOT NULL AUTO_INCREMENT,
  payment_no VARCHAR(32) NOT NULL COMMENT '我方支付单号',
  order_no VARCHAR(32) NOT NULL,
  user_id BIGINT NOT NULL,
  channel VARCHAR(16) NOT NULL DEFAULT 'MOCK' COMMENT 'MOCK/ALIPAY/WECHAT',
  amount DECIMAL(10,2) NOT NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'INIT' COMMENT 'INIT/SUCCESS/FAIL/CLOSED',
  channel_trade_no VARCHAR(64) COMMENT '渠道流水号(幂等关键)',
  callback_raw TEXT,
  paid_time DATETIME,
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_payment_no(payment_no),
  UNIQUE KEY uk_channel_trade(channel, channel_trade_no)  -- 同一渠道流水只处理一次
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='支付单';

CREATE TABLE pay_refund (
  id BIGINT NOT NULL AUTO_INCREMENT,
  refund_no VARCHAR(32) NOT NULL,
  order_no VARCHAR(32) NOT NULL,
  payment_no VARCHAR(32) NOT NULL,
  amount DECIMAL(10,2) NOT NULL,
  reason VARCHAR(255),
  status VARCHAR(16) NOT NULL DEFAULT 'APPLY' COMMENT 'APPLY/APPROVED/SUCCESS/REJECT',
  channel_refund_no VARCHAR(64),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY(id), UNIQUE KEY uk_refund_no(refund_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='退款单';

CREATE TABLE tk_ticket (
  id BIGINT NOT NULL AUTO_INCREMENT,
  ticket_no VARCHAR(40) NOT NULL,
  order_id BIGINT NOT NULL,
  order_item_id BIGINT NOT NULL,
  event_id BIGINT NOT NULL,
  seat_id BIGINT COMMENT '站票为空',
  viewer_id BIGINT,
  qr_token VARCHAR(64) NOT NULL COMMENT '随机不可枚举',
  status VARCHAR(16) NOT NULL DEFAULT 'VALID' COMMENT 'VALID/USED/REFUNDED',
  checkin_time DATETIME,
  checkin_device VARCHAR(64),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id),
  UNIQUE KEY uk_ticket_no(ticket_no),
  UNIQUE KEY uk_qr(qr_token)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='电子票';
```

对应实体 `Payment`、`Refund`、`Ticket` 与 Mapper 按前几卷风格创建。

---

## 6. 支付 Service：发起支付

路径：`payment/model/vo/PayCreateVO.java`：`paymentNo`、`payUrl`、`amount`。

路径：`payment/service/PaymentService.java`（接口，方法：createPayment、handleMockCallback、queryStatus、applyRefund）。

路径：`payment/service/impl/PaymentServiceImpl.java`（片段）

```java
@Transactional(rollbackFor = Exception.class)
public PayCreateVO createPayment(Long userId, String orderNo, String channel) {
    Orders order = orderMapper.selectOne(Wrappers.<Orders>lambdaQuery()
        .eq(Orders::getOrderNo, orderNo).eq(Orders::getUserId, userId));
    if (order == null) throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);
    if (!OrderStatus.WAIT_PAY.name().equals(order.getStatus())) {
        throw new BusinessException(OrderErrorCode.ORDER_STATUS_ILLEGAL);
    }

    Payment pay = new Payment();
    pay.setPaymentNo(OrderNoGenerator.payNo());
    pay.setOrderNo(orderNo);
    pay.setUserId(userId);
    pay.setChannel(channel);
    pay.setAmount(order.getPayAmount());
    pay.setStatus("INIT");
    paymentMapper.insert(pay);

    // 本地消息此处不需要（支付成功时才发）；返回模拟收银台URL
    PayCreateVO vo = new PayCreateVO();
    vo.setPaymentNo(pay.getPaymentNo());
    vo.setAmount(order.getPayAmount());
    vo.setPayUrl(payProps.getBaseUrl() + "/mock/cashier?paymentNo=" + pay.getPaymentNo());
    return vo;
}
```

Controller：
```java
@PostMapping("/create")
public Result<PayCreateVO> create(@CurrentUser Long uid,
        @RequestParam String orderNo,
        @RequestParam(defaultValue="MOCK") String channel) {
    return Result.success(paymentService.createPayment(uid, orderNo, channel));
}
```

---

## 7. 模拟支付网关与异步回调

学习期做一个**极简模拟网关**：一个收银台页面 + 一个「模拟渠道服务器」回调你的系统。

路径：`payment/controller/MockGatewayController.java`

```java
@Controller   // 注意是 @Controller：要返回一个HTML收银台页面，不是JSON
@RequestMapping("/mock")
@RequiredArgsConstructor
public class MockGatewayController {

    private final PaymentMapper paymentMapper;

    @GetMapping("/cashier")
    public String cashier(@RequestParam String paymentNo, Model model) {
        Payment p = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
            .eq(Payment::getPaymentNo, paymentNo));
        model.addAttribute("payment", p);
        return "mock_cashier";   // src/main/resources/templates/mock_cashier.html(Thymeleaf) 或直接拼字符串
    }
}
```

为减少依赖，本卷不用 Thymeleaf，直接用一个 `@RestController` 返回 HTML 字符串（或放静态页用 JS 调回调）。推荐静态页 `static/mock/cashier.html`：

```html
<body style="background:#0A0A0F;color:#fff;font-family:sans-serif;text-align:center;padding:80px">
  <h2>模拟支付收银台</h2>
  <p>支付单号：<span id="pno"></span></p>
  <p>金额：¥<span id="amt"></span></p>
  <button id="pay" style="padding:14px 40px;font-size:18px;background:#E63946;color:#fff;border:0">
    模拟支付成功
  </button>
<script>
  const pno = new URLSearchParams(location.search).get('paymentNo');
  document.getElementById('pno').textContent = pno;
  fetch('/api/v1/payments/'+pno+'/info').then(r=>r.json()).then(j=>{
    document.getElementById('amt').textContent = j.data.amount;
  });
  document.getElementById('pay').onclick = async () => {
    // 以"渠道服务器"身份调用后端回调（真实环境由支付宝服务器调用）
    const channelTradeNo = 'MOCK' + Date.now();
    const sign = await (crypto.subtle.digest('SHA-256',
       new TextEncoder().encode(pno + channelTradeNo + 'MARS_MOCK_SECRET'))
    ).then(buf=>Array.from(new Uint8Array(buf)).map(b=>b.toString(16).padStart(2,'0')).join(''));
    const resp = await fetch('/api/v1/pay/callback/mock', {
      method:'POST', headers:{'Content-Type':'application/json'},
      body: JSON.stringify({ paymentNo:pno, channelTradeNo, sign, paidTime:new Date().toISOString() })
    });
    const j = await resp.json();
    alert(j.code===0 ? '支付成功' : ('失败：'+j.message));
    if (j.code===0) location.href='/pages/orders.html?no='+j.data;
  };
</script>
</body>
```

> 真实环境回调是**服务器到服务器**，不经过浏览器；这里用浏览器代发仅为了学习演示。验签仍照做，体会「不能信任请求内容」。

---

## 8. 回调幂等与支付成功主流程

路径：`payment/service/impl/PaymentServiceImpl.java`（续）

```java
@Transactional(rollbackFor = Exception.class)
public String handleMockCallback(MockCallbackDTO dto) {
    // ① 验签：服务端用同样规则重算签名比对（密钥不能下发前端，真实场景密钥在服务端/渠道）
    String expect = SignUtil.sha256(dto.getPaymentNo() + dto.getChannelTradeNo() + "MARS_MOCK_SECRET");
    if (!expect.equalsIgnoreCase(dto.getSign())) {
        throw new BusinessException(CommonErrorCode.FORBIDDEN.getCode(), "回调验签失败");
    }

    Payment pay = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
        .eq(Payment::getPaymentNo, dto.getPaymentNo()));
    if (pay == null) throw new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND);

    // ② 幂等：渠道流水唯一 + 状态判断。重复回调直接返回成功(渠道要求ACK success)
    if ("SUCCESS".equals(pay.getStatus())) {
        return pay.getOrderNo();
    }
    Payment dup = paymentMapper.selectOne(Wrappers.<Payment>lambdaQuery()
        .eq(Payment::getChannelTradeNo, dto.getChannelTradeNo()));
    if (dup != null) return pay.getOrderNo();

    // ③ 更新支付单
    pay.setStatus("SUCCESS");
    pay.setChannelTradeNo(dto.getChannelTradeNo());
    pay.setCallbackRaw(JSON.toJSONString(dto));
    pay.setPaidTime(LocalDateTime.now());
    paymentMapper.updateById(pay);

    // ④ 订单状态机 WAIT_PAY → PAID
    Orders order = orderMapper.selectOne(Wrappers.<Orders>lambdaQuery()
        .eq(Orders::getOrderNo, pay.getOrderNo()));
    if (OrderStatus.WAIT_PAY.name().equals(order.getStatus())) {
        orderService.transit(order.getId(), OrderEvent.PAY_SUCCESS, "MOCK");
    }

    // ⑤ 同事务写本地消息(PENDING)，事务提交后再发 MQ（见 afterCommit）
    PaySuccessEvent event = new PaySuccessEvent(pay.getOrderNo(), order.getUserId(),
            pay.getPaymentNo(), pay.getAmount().toPlainString());
    Long msgId = null;
    // 直接 insert 本地消息并拿回id
    LocalMessage m = new LocalMessage();
    m.setBizNo(pay.getOrderNo());
    m.setExchange(MqConstants.PAY_EXCHANGE);
    m.setRoutingKey(MqConstants.RK_PAY_SUCCESS);
    m.setPayload(JSON.toJSONString(event));
    m.setStatus("PENDING");
    m.setNextRetry(LocalDateTime.now());
    localMessageMapper.insert(m);
    msgId = m.getId();

    // ⑥ 事务提交后发送（保证库里有支付成功状态，消费者收到消息时一定能查到订单）
    TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
        @Override
        public void afterCommit() {
            reliableMessageSender.send(msgId);
        }
    });
    return pay.getOrderNo();
}
```

回调 Controller（**必须在 Security 白名单**，因为是渠道服务器调用，没有用户 token）：
```java
@PostMapping("/callback/mock")
public Result<String> mockCallback(@RequestBody MockCallbackDTO dto) {
    return Result.success(paymentService.handleMockCallback(dto));
}
```
把 `/api/v1/pay/callback/**` 加入 `SecurityConfig` 白名单。

> `afterCommit` 很关键：若在事务提交前发消息，下游消费者可能立刻回查订单却查不到（事务还没提交、读不到），造成空窗。提交后发，消息与数据必然一致可见。

---

## 9. 支付成功事件扇出（出票/积分/通知）

事件体（`common/mq/event/PaySuccessEvent.java`）：orderNo、userId、paymentNo、amount。

---

## 10. 消费者：手动 ACK、幂等、重试、死信

### 10.1 出票消费者（核心）
路径：`ticket/mq/TicketCreateConsumer.java`

```java
@Slf4j
@Component
@RequiredArgsConstructor
@RabbitListener(queues = MqConstants.Q_TICKET_CREATE)
public class TicketCreateConsumer {

    private final TicketService ticketService;

    @RabbitHandler
    public void on(String body, Channel channel,
                   @Header(AmqpHeaders.DELIVERY_TAG) long tag) throws IOException {
        try {
            PaySuccessEvent e = JSON.parseObject(body, PaySuccessEvent.class);
            ticketService.issueTickets(e.getOrderNo());   // 内部幂等
            channel.basicAck(tag, false);
        } catch (Exception ex) {
            log.error("出票消费失败，转入死信 body={}", body, ex);
            // nack 且不重回队列 → 进死信队列，由人工/补偿处理
            channel.basicNack(tag, false, false);
        }
    }
}
```

### 10.2 出票 Service（幂等：一张明细一张票）
路径：`ticket/service/impl/TicketServiceImpl.java`

```java
@Transactional(rollbackFor = Exception.class)
public void issueTickets(String orderNo) {
    Orders order = orderMapper.selectOne(Wrappers.<Orders>lambdaQuery()
        .eq(Orders::getOrderNo, orderNo));
    if (order == null) throw new BusinessException(OrderErrorCode.ORDER_NOT_FOUND);

    List<OrderItem> items = orderItemMapper.selectList(
        Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, order.getId()));

    boolean anyNew = false;
    for (OrderItem it : items) {
        for (int i = 0; i < it.getQuantity(); i++) {
            // 幂等：同一 orderItem 已有票则跳过（消息重复投递安全）
            Long exist = ticketMapper.countByOrderItem(it.getId());
            if (exist != null && exist > i) continue;

            Ticket t = new Ticket();
            t.setTicketNo(OrderNoGenerator.ticketNo());
            t.setOrderId(order.getId());
            t.setOrderItemId(it.getId());
            t.setEventId(it.getEventId());
            t.setSeatId(it.getSeatId());
            t.setViewerId(it.getViewerId());
            t.setQrToken(UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", ""));   // 64位随机，不可枚举
            t.setStatus("VALID");
            ticketMapper.insert(t);
            anyNew = true;
        }
    }

    // 座位置 SOLD（坐票），EventTier 已售+1（站票，支付成功才真正落 sold）
    confirmInventorySold(order, items);

    if (anyNew) {
        orderService.transit(order.getId(), OrderEvent.TICKET_SUCCESS, "SYSTEM");
    }
}
```

> 更严谨的幂等：给 `tk_ticket(order_item_id, seq)` 加唯一索引，重复插入直接冲突捕获。上面用「计数判断」便于理解，生产建议唯一索引兜底。

### 10.3 积分与通知消费者
`PointAddConsumer`（监听 Q_POINT_ADD，本卷先 `log.info("加积分...")`，第 6 卷实现积分账户）。
`NotifySendConsumer`（监听 Q_NOTIFY_SEND，调用消息中心发「支付成功」通知）。
两者同样 try-catch + 手动 ack/nack。

### 10.4 死信监控
给死信队列加一个消费者只记录告警（或接管理后台列表），便于发现失败消息：
```java
@RabbitListener(queues = MqConstants.Q_TICKET_CREATE_DLQ)
public void onDlq(String body) {
    log.error("【死信告警】出票失败消息: {}", body);
}
```

---

## 11. 电子票二维码（zxing）

路径：`common/utils/QrCodeUtil.java`

```java
public class QrCodeUtil {
    public static byte[] generate(String content, int size) throws WriterException {
        Map<EncodeHintType,Object> hints = Map.of(
            EncodeHintType.CHARACTER_SET, "UTF-8",
            EncodeHintType.MARGIN, 1);
        BitMatrix matrix = new MultiFormatWriter()
            .encode(content, BarcodeFormat.QR_CODE, size, size, hints);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
        } catch (IOException e) { throw new RuntimeException(e); }
        return out.toByteArray();
    }
}
```

票详情接口返回二维码图片（内容是加密/随机 token，不是订单号）：
```java
@GetMapping("/tickets/{ticketNo}/qrcode")
public void qrcode(@CurrentUser Long uid, @PathVariable String ticketNo,
                   HttpServletResponse resp) throws Exception {
    Ticket t = ticketService.getOwnedTicket(uid, ticketNo);   // 必须本人
    byte[] img = QrCodeUtil.generate(t.getQrToken(), 260);
    resp.setContentType("image/png");
    resp.getOutputStream().write(img);
}
```

我的票列表：`GET /tickets`（按 userId 查），返回票号、场次、座位/区域、状态。前端 `orders.html` 支付成功后展示票与二维码。

---

## 12. 扫码核销（防重复）

工作人员端（B 端）扫到 qr_token 后调用核销接口。用 Redis 原子 SETNX 防高并发重复核销：

```java
@PreAuthorize("hasRole('ADMIN')")   // 或专门的检票员角色
@PostMapping("/admin/tickets/checkin")
public Result<Void> checkin(@RequestParam String token,
                            @RequestHeader("X-Device") String device) {
    ticketService.checkin(token, device);
    return Result.success();
}
```

Service：
```java
@Transactional(rollbackFor = Exception.class)
public void checkin(String token, String device) {
    Ticket t = ticketMapper.selectOne(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getQrToken, token));
    if (t == null) throw new BusinessException(TicketErrorCode.TICKET_NOT_FOUND);

    // Redis 原子防重（开场高峰集中入场，必须抗并发）
    Boolean first = redis.setIfAbsent("mars:ticket:used:" + token, "1", 24*3600L);
    if (Boolean.FALSE.equals(first)) {
        throw new BusinessException(TicketErrorCode.TICKET_USED);
    }
    if (!"VALID".equals(t.getStatus())) {
        throw new BusinessException(TicketErrorCode.TICKET_NOT_VALID);
    }
    t.setStatus("USED");
    t.setCheckinTime(LocalDateTime.now());
    t.setCheckinDevice(device);
    ticketMapper.updateById(t);
}
```

> 即便不使用 Redis，也应在 DB 用 `UPDATE ... WHERE id=? AND status='VALID'` 条件更新保证只成功一次；Redis 只是把高并发挡在前面。

---

## 13. 退款流程

```java
@Transactional(rollbackFor = Exception.class)
public void applyRefund(Long userId, String orderNo, String reason) {
    Orders order = ...查到本人订单;
    // 仅 PAID/TICKETED 可申请（走状态机到 REFUNDING）
    orderService.transit(order.getId(), OrderEvent.APPLY_REFUND, String.valueOf(userId));
    Refund r = new Refund();
    r.setRefundNo(OrderNoGenerator.refundNo());
    r.setOrderNo(orderNo); r.setPaymentNo(...); r.setAmount(order.getPayAmount());
    r.setReason(reason); r.setStatus("APPLY");
    refundMapper.insert(r);
    // 学习期：自动审核通过并走模拟退款；真实需客服审核
    doMockRefund(r);
}

private void doMockRefund(Refund r) {
    r.setStatus("SUCCESS");
    r.setChannelRefundNo("MOCKRF" + System.currentTimeMillis());
    refundMapper.updateById(r);
    // 订单 REFUNDING→REFUNDED；释放票/座位；EventTier 库存回补（发 MQ refund.success 复用释放逻辑）
    Orders order = orderMapper.selectOne(...orderNo);
    orderService.transit(order.getId(), OrderEvent.REFUND_SUCCESS, "MOCK");
    ticketService.markRefunded(order.getId());
    inventoryReleaseService.releasePaid(order);   // 已售库存回补
}
```

> 真实退款也要接渠道**退款回调**并幂等（同支付回调套路：渠道退款流水唯一索引 + 状态判断）。本卷模拟同步成功，理解状态流转即可。

---

## 14. 消息中心（模板/记录/邮件/短信mock）

### 14.1 建表
```sql
CREATE TABLE msg_template (
  id BIGINT NOT NULL AUTO_INCREMENT,
  code VARCHAR(64) NOT NULL,        // PAY_SUCCESS / REFUND_SUCCESS
  channel VARCHAR(16) NOT NULL,     // EMAIL/SMS/INBOX
  title VARCHAR(128),
  content VARCHAR(512) NOT NULL,    // 占位 ${name} ${orderNo}
  PRIMARY KEY(id), UNIQUE KEY uk_code_channel(code,channel)
);
CREATE TABLE msg_record (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT, channel VARCHAR(16), template_code VARCHAR(64),
  target VARCHAR(128), content TEXT, status VARCHAR(16) DEFAULT 'PENDING',
  retry_count INT DEFAULT 0, remark VARCHAR(255),
  create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(id), KEY idx_user(user_id)
);
INSERT INTO msg_template(code,channel,title,content) VALUES
 ('PAY_SUCCESS','INBOX','支付成功','您的订单 ${orderNo} 已支付成功，电子票已生成。'),
 ('PAY_SUCCESS','EMAIL','Mars Concert 购票成功','Hello ${name}, 订单 ${orderNo} 已出票，请凭二维码入场。');
```

### 14.2 通知消费者调用消息中心
`NotifySendConsumer` → `NotifyService.sendOnPaySuccess(userId, orderNo)`：
- 渲染模板（替换 `${...}`）；
- INBOX：写 msg_record（前端"我的消息"可读）；
- EMAIL：JavaMailSender 发送（try-catch，未配置则降级为日志）；
- SMS：本卷只 `log.info("【短信mock】...")`。

邮件发送示例：
```java
@Service
@RequiredArgsConstructor
public class MailNotifier {
    private final JavaMailSender mailSender;
    @Value("${spring.mail.username:}") private String from;

    public void send(String to, String title, String content) {
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from); m.setTo(to); m.setSubject(title); m.setText(content);
            mailSender.send(m);
        } catch (Exception e) {
            // 学习期未配置SMTP时降级日志，不影响主链路
            log.warn("邮件发送失败(降级日志) to={} title={} err={}", to, title, e.getMessage());
        }
    }
}
```

> 通知必须异步（消费者里做），绝不阻塞支付主事务；失败只重试通知本身，不影响已支付订单。

---

## 15. 测试与排错

`http/pay.http`：
```http
### 1. 先下单拿 orderNo（设置变量）
POST http://localhost:8080/api/v1/orders
Authorization: Bearer {{token}}
Content-Type: application/json
{ "eventId":1, "idempotentKey":"pay-test-1", "items":[{"tierId":1,"quantity":1}] }
# > {% client.global.set("orderNo", response.body.data); %}

### 2. 发起支付
POST http://localhost:8080/api/v1/payments/create?orderNo={{orderNo}}&channel=MOCK
Authorization: Bearer {{token}}
# > {% client.global.set("payUrl", response.body.data.payUrl); %}

### 3. 浏览器打开模拟收银台（返回的 payUrl），点"模拟支付成功"

### 4. 轮询订单状态（应 TICKETED）
GET http://localhost:8080/api/v1/orders/{{orderNo}}
Authorization: Bearer {{token}}

### 5. 查我的票（应有票号与 qrToken）
GET http://localhost:8080/api/v1/tickets
Authorization: Bearer {{token}}

### 6. 取二维码图片（浏览器直接访问，需带token，可用浏览器插件或先在页面img标签中）
GET http://localhost:8080/api/v1/tickets/{{ticketNo}}/qrcode
Authorization: Bearer {{token}}

### 7. 核销（管理员token），同一token调两次，第二次应报"已核销"
POST http://localhost:8080/api/v1/admin/tickets/checkin?token=xxx
Authorization: Bearer {{adminToken}}
```

验收要点：
- RabbitMQ 控制台 15672 的 Queues 页能看到 3 个业务队列与死信队列；
- 支付成功后三个队列各有 1 条消息被 ack，订单变为 TICKETED，生成票；
- **重复点击/重发回调**：支付单仍一条、票不重复（幂等）；
- 临时在出票消费者里 `throw new RuntimeException`，消息应进死信队列而非无限循环；
- 未支付订单无票；核销两次第二次报错。

排查表：
| 现象 | 原因/解决 |
|---|---|
| 回调 401/403 | `/api/v1/pay/callback/**` 没加白名单 |
| 消费者收不到 | 交换机/队列/绑定未声明成功，看 15672 拓扑；routingKey 是否一致 |
| 消息重复消费导致重复出票 | 消费者必须幂等（唯一索引或状态判断），MQ 保证 at-least-once |
| 消息一直 unacked | 手动 ACK 模式没调 basicAck，或异常没 nack |
| 消费失败无限重投 | nack 时 requeue 应为 false，让其进死信 |
| 消费者查不到刚支付订单 | 消息在事务提交前发了；用 afterCommit |
| 邮件报错 | SMTP 授权码非登录密码；未配则已做降级日志 |
| 二维码乱码/打不开 | 接口要写 image/png 且直接写字节流；鉴权用 img 标签时注意 token |
| confirm 不触发 | yml publisher-confirm-type=correlated；CorrelationData 要传入 |

### Checklist
- [ ] 发起支付→模拟页→回调→订单 TICKETED 全链路通
- [ ] 回调验签失败被拒
- [ ] 重复回调幂等（支付单/票都不重复）
- [ ] 支付成功一条消息扇出出票/积分/通知三队列
- [ ] 出票消费失败进死信并有告警日志
- [ ] 本地消息表 PENDING→SENT，补偿任务可重发未确认消息
- [ ] 电子票二维码为随机 token，本人可见
- [ ] 核销防重（Redis SETNX + DB 条件）
- [ ] 退款后订单 REFUNDED、票失效、库存回补

---

## 下一卷预告（第 6 卷：社区/营销/搜索/文件）
Spring WebSocket（STOMP）实时弹幕与应援墙、敏感词 DFA 过滤与集群广播；优惠券领取/核销与价格引擎接入、积分签到；**秒杀 Redis 预减库存 + MQ 异步下单**；Elasticsearch 中文搜索（IK 分词、联想、热词）；MinIO/本地上传文件与签名 URL；EasyExcel 订单导出；操作日志 AOP。
