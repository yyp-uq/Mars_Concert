package com.mars.concert.common.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 签名工具：SHA-256 十六进制小写摘要。
 * 模拟网关与回调验签共用：sign = sha256(paymentNo + channelTradeNo + 密钥)。
 */
public final class SignUtil {

    private SignUtil() {
    }

    public static String sha256(String content) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 计算失败", e);
        }
    }
}
