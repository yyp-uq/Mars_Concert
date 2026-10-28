package com.mars.concert.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.enums.CommonErrorCode;
import com.mars.concert.common.exception.BusinessException;
import com.mars.concert.common.mq.entity.LocalMessage;
import com.mars.concert.common.mq.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 可靠消息发送器：本地消息表 + publisher confirm。
 * 业务在事务内调用 savePending 落库一条 PENDING（与业务数据同事务），
 * 事务提交后（afterCommit）再调用 send 投递；confirm 确认 broker 收到后置为 SENT，未确认由补偿任务重发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReliableMessageSender {

    private final RabbitTemplate rabbitTemplate;
    private final LocalMessageMapper localMessageMapper;
    private final ObjectMapper objectMapper;

    /** 落库一条 PENDING 本地消息，返回实体（含自增 id） */
    public LocalMessage savePending(String bizNo, String exchange, String rk, Object payload) {
        LocalMessage m = new LocalMessage();
        m.setBizNo(bizNo);
        m.setExchange(exchange);
        m.setRoutingKey(rk);
        try {
            m.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            throw new BusinessException(CommonErrorCode.SYSTEM_ERROR);
        }
        m.setStatus("PENDING");
        m.setRetryCount(0);
        m.setNextRetry(LocalDateTime.now());
        localMessageMapper.insert(m);
        return m;
    }

    /** 投递并带 confirm 确认 broker 收到 */
    public void send(Long messageId) {
        LocalMessage m = localMessageMapper.selectById(messageId);
        if (m == null || "SENT".equals(m.getStatus())) return;

        CorrelationData cd = new CorrelationData(String.valueOf(m.getId()));
        cd.getFuture().whenComplete((result, ex) -> {
            if (ex != null || result == null || !result.isAck()) {
                // 未确认：保持 PENDING，由 LocalMessageRetryJob 补偿重发
                log.error("消息投递未确认 id={} ex={}", messageId, ex == null ? "" : ex.getMessage());
            } else {
                LocalMessage up = new LocalMessage();
                up.setId(m.getId());
                up.setStatus("SENT");
                localMessageMapper.updateById(up);
            }
        });
        rabbitTemplate.convertAndSend(m.getExchange(), m.getRoutingKey(), m.getPayload(), cd);
    }
}
