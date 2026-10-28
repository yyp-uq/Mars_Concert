package com.mars.concert.notify.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

/**
 * 邮件发送：学习期未配置 SMTP 时 catch 住降级为日志，不影响支付主链路（通知必须异步、可失败）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MailNotifier {

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username:}")
    private String from;

    public void send(String to, String title, String content) {
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            m.setFrom(from);
            m.setTo(to);
            m.setSubject(title);
            m.setText(content);
            mailSender.send(m);
            log.info("邮件已发送 to={} title={}", to, title);
        } catch (Exception e) {
            // 学习期未配置SMTP时降级日志，不影响主链路
            log.warn("邮件发送失败(降级日志) to={} title={} err={}", to, title, e.getMessage());
        }
    }
}
