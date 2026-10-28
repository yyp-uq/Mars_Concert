package com.mars.concert.community.filter;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mars.concert.community.entity.SensitiveWord;
import com.mars.concert.community.mapper.SensitiveWordMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 敏感词 DFA（确定有限自动机）过滤器。
 *
 * 原理：把词库构建成字符树（Trie + 词尾标记），匹配时对文本一次扫描即可命中所有词，
 * 复杂度 O(n)，远优于对每个词反复 contains 的 O(n*m)。
 *
 * 匹配策略：最长匹配（如"退票中介费"中的"退票中介"优先于"退票"），命中片段替换为等长 *。
 * 词库来源：@PostConstruct 从 cm_sensitive_word 表加载；表为空（学习期未初始化）用内置兜底词。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SensitiveWordFilter {

    private final SensitiveWordMapper sensitiveWordMapper;

    /** 词树根节点。value 可能是下一层 Map 或词尾标记 END */
    private final Map<Character, Object> root = new HashMap<>();
    /** 词尾标记：挂在 Map 里的一个特殊 key（用不可见字符，避免与真实词字符冲突） */
    private static final char END_FLAG = '\u0000';

    /** 表为空时的兜底词库（保证过滤器开箱可用） */
    private static final List<String> FALLBACK_WORDS = List.of("黄牛", "退票中介", "假票", "刷票", "代抢", "骗子");

    @PostConstruct
    public void init() {
        List<String> words;
        try {
            words = sensitiveWordMapper.selectList(Wrappers.<SensitiveWord>lambdaQuery()
                            .eq(SensitiveWord::getStatus, 1))
                    .stream().map(SensitiveWord::getWord).toList();
        } catch (Exception e) {
            // 表不存在等异常不阻断启动，退化为兜底词库
            log.warn("敏感词表加载失败，使用内置兜底词库: {}", e.getMessage());
            words = List.of();
        }
        if (words.isEmpty()) {
            log.warn("敏感词表为空，使用内置兜底词库 {} 个词", FALLBACK_WORDS.size());
            words = FALLBACK_WORDS;
        }
        words.forEach(this::addWord);
        log.info("敏感词 DFA 初始化完成，词数={}", words.size());
    }

    /** 向词树插入一个词 */
    @SuppressWarnings("unchecked")
    private void addWord(String word) {
        Map<Character, Object> node = root;
        for (int i = 0; i < word.length(); i++) {
            char c = word.charAt(i);
            Object next = node.get(c);
            if (!(next instanceof Map)) {
                Map<Character, Object> child = new HashMap<>();
                node.put(c, child);
                node = child;
            } else {
                node = (Map<Character, Object>) next;
            }
            if (i == word.length() - 1) {
                node.put(END_FLAG, Boolean.TRUE);   // 词尾打标
            }
        }
    }

    /**
     * 过滤：命中敏感词的片段替换为等长 *；无命中返回原文。
     * null 输入返回空串（调用方判空后拒绝发送）。
     */
    public String filter(String text) {
        if (text == null) return "";
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            int len = matchLen(chars, i);
            if (len > 0) {
                for (int k = i; k < i + len; k++) chars[k] = '*';
                i += len - 1;   // 跳过已替换片段，避免二次匹配
            }
        }
        return new String(chars);
    }

    /** 从 start 位置开始沿词树走，返回命中的最长词长度，未命中返回 0 */
    @SuppressWarnings("unchecked")
    private int matchLen(char[] chars, int start) {
        Map<Character, Object> node = root;
        int len = 0, matched = 0;
        for (int i = start; i < chars.length; i++) {
            Object next = node.get(chars[i]);
            if (!(next instanceof Map)) break;
            len++;
            node = (Map<Character, Object>) next;
            if (Boolean.TRUE.equals(node.get(END_FLAG))) matched = len;   // 记录最长命中
        }
        return matched;
    }
}
