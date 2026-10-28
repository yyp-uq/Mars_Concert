package com.mars.auth.sms;

import com.aliyun.dysmsapi20170525.Client;
import com.aliyun.dysmsapi20170525.models.SendSmsRequest;
import com.aliyun.dysmsapi20170525.models.SendSmsResponse;
import com.aliyun.teaopenapi.models.Config;
import com.mars.common.enums.CommonErrorCode;
import com.mars.common.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 阿里云短信发送器。
 * 开关关闭（默认）时退化为日志模拟，便于本地开发；开启后走真实 SendSms。
 */
@Slf4j
@Component
public class AliyunSmsSender {

    @Value("${mars.sms.enabled:false}")
    private boolean enabled;
    @Value("${mars.sms.access-key-id:}")
    private String accessKeyId;
    @Value("${mars.sms.access-key-secret:}")
    private String accessKeySecret;
    @Value("${mars.sms.sign-name:}")
    private String signName;
    @Value("${mars.sms.template-code:}")
    private String templateCode;

    private Client client;

    @PostConstruct
    public void init() {
        if (!enabled) {
            log.info("阿里云短信未开启（mars.sms.enabled=false），验证码走日志模拟");
            return;
        }
        if (isBlank(accessKeyId) || isBlank(accessKeySecret) || isBlank(signName) || isBlank(templateCode)) {
            throw new IllegalStateException("已开启阿里云短信，但 AccessKey/签名/模板未配置完整");
        }
        try {
            Config config = new Config()
                    .setAccessKeyId(accessKeyId)
                    .setAccessKeySecret(accessKeySecret);
            config.endpoint = "dysmsapi.aliyuncs.com";
            this.client = new Client(config);
            log.info("阿里云短信已开启，签名={} 模板={}", signName, templateCode);
        } catch (Exception e) {
            throw new IllegalStateException("阿里云短信客户端初始化失败", e);
        }
    }

    /** 发送登录验证码；未开启时只打印日志 */
    public void sendLoginCode(String phone, String code) {
        if (!enabled) {
            log.info("【火星演唱会】手机号 {} 的登录验证码为：{}（5分钟内有效）", phone, code);
            return;
        }
        try {
            SendSmsRequest request = new SendSmsRequest()
                    .setPhoneNumbers(phone)
                    .setSignName(signName)
                    .setTemplateCode(templateCode)
                    .setTemplateParam("{\"code\":\"" + code + "\"}");
            SendSmsResponse response = client.sendSms(request);
            String respCode = response.getBody().getCode();
            if (!"OK".equals(respCode)) {
                log.warn("阿里云短信发送失败 phone={} code={} message={}", phone, respCode, response.getBody().getMessage());
                throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(),
                        "短信发送失败：" + response.getBody().getMessage());
            }
            log.info("阿里云短信发送成功 phone={} bizId={}", phone, response.getBody().getBizId());
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("阿里云短信调用异常 phone={}", phone, e);
            throw new BusinessException(CommonErrorCode.PARAM_INVALID.getCode(), "短信发送失败，请稍后重试");
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
