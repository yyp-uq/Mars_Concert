package com.mars.concert.common.mq;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.common.mq.entity.LocalMessage;
import com.mars.concert.common.mq.mapper.LocalMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息补偿任务：扫描 PENDING 且到期未发送成功的消息重发（指数退避），保证「不丢」。
 */
@Slf4j
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
            try {
                sender.send(m.getId());
                // 同时推进下次重试时间，避免同一批未确认消息被每轮反复投递
                LocalMessage up = new LocalMessage();
                up.setId(m.getId());
                up.setRetryCount(m.getRetryCount() + 1);
                up.setNextRetry(LocalDateTime.now().plusSeconds((long) Math.pow(2, m.getRetryCount())));
                mapper.updateById(up);
            } catch (Exception e) {
                log.error("本地消息重发失败 id={}", m.getId(), e);
            }
        }
    }
}
