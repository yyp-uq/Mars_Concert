package com.mars.concert.notify.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.member.entity.User;
import com.mars.concert.member.mapper.UserMapper;
import com.mars.concert.notify.entity.MsgRecord;
import com.mars.concert.notify.entity.MsgTemplate;
import com.mars.concert.notify.mapper.MsgRecordMapper;
import com.mars.concert.notify.mapper.MsgTemplateMapper;
import com.mars.concert.notify.service.MailNotifier;
import com.mars.concert.notify.service.NotifyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotifyServiceImpl implements NotifyService {

    private static final String CODE_PAY_SUCCESS = "PAY_SUCCESS";

    private final MsgTemplateMapper templateMapper;
    private final MsgRecordMapper recordMapper;
    private final MailNotifier mailNotifier;
    private final UserMapper userMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void sendOnPaySuccess(Long userId, String orderNo) {
        User user = userId == null ? null : userMapper.selectById(userId);
        String name = (user != null && user.getNickname() != null) ? user.getNickname() : "火星乐迷";
        String phone = user == null ? null : user.getPhone();

        List<MsgTemplate> templates = templateMapper.selectList(
                Wrappers.<MsgTemplate>lambdaQuery().eq(MsgTemplate::getCode, CODE_PAY_SUCCESS));
        for (MsgTemplate tp : templates) {
            String title = render(tp.getTitle(), name, orderNo);
            String content = render(tp.getContent(), name, orderNo);
            switch (tp.getChannel()) {
                case "INBOX" -> saveRecord(userId, tp, null, content, "SENT");
                case "EMAIL" -> {
                    String to = phone != null ? phone + "@mars.local" : "user" + userId + "@mars.local";
                    mailNotifier.send(to, title, content);
                    saveRecord(userId, tp, to, content, "SENT");
                }
                case "SMS" -> {
                    log.info("【短信mock】to={} 内容={}", phone, content);
                    saveRecord(userId, tp, phone, content, "SENT");
                }
                default -> log.warn("未知通知渠道 {}", tp.getChannel());
            }
        }
        log.info("支付成功通知完成 userId={} orderNo={}", userId, orderNo);
    }

    private void saveRecord(Long userId, MsgTemplate tp, String target, String content, String status) {
        MsgRecord r = new MsgRecord();
        r.setUserId(userId);
        r.setChannel(tp.getChannel());
        r.setTemplateCode(tp.getCode());
        r.setTarget(target);
        r.setContent(content);
        r.setStatus(status);
        r.setRetryCount(0);
        recordMapper.insert(r);
    }

    private String render(String tpl, String name, String orderNo) {
        if (tpl == null) return null;
        return tpl.replace("${name}", name == null ? "" : name)
                  .replace("${orderNo}", orderNo == null ? "" : orderNo);
    }
}
