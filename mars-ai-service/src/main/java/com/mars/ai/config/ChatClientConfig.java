package com.mars.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

/**
 * ChatClient / ChatMemory / VectorStore / RAG 顾问装配。
 */
@Configuration
public class ChatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatClientConfig.class);

    /** 向量库本地落盘文件（SimpleVectorStore 学习用，生产换 Redis/PgVector） */
    public static final String VECTOR_STORE_FILE = "data/vectorstore-faq.json";

    /** 火星小助手人设（手册 §3 原文） */
    private static final String SYSTEM = """
        你是「火星演唱会」官方智能客服，名叫「火星小助手」，语气热情、简洁、可靠。
        服务对象是华晨宇「火星演唱会」的歌迷，负责解答购票、选座、支付、退票、入场、歌单等问题。
        规则：
        1. 只回答与本平台演唱会服务相关的问题；无关问题礼貌拒绝。
        2. 涉及订单、余票、退款等实时信息时，必须调用提供的工具查询，禁止编造。
        3. 不知道就说不知道，并引导联系人工客服（工作时间 9:00-21:00）。
        4. 退款等资金操作必须先向用户确认，得到明确同意后才执行。
        """;

    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.defaultSystem(SYSTEM).build();
    }

    /**
     * 滑动窗口记忆：保留最近 20 条消息（10 轮 user+assistant）。
     * 仓库实现是 RedisChatMemoryRepository，重启不丢上下文。
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    /**
     * 内存向量库：启动时若存在落盘文件则加载，否则从空库开始（由 KnowledgeSeeder 首次灌入）。
     */
    @Bean
    public SimpleVectorStore vectorStore(EmbeddingModel embeddingModel) {
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();
        File file = new File(VECTOR_STORE_FILE);
        if (file.exists()) {
            try {
                store.load(file);
                log.info("已加载本地向量库：{}", file.getAbsolutePath());
            } catch (Exception e) {
                log.warn("加载本地向量库失败，将以空库启动：{}", e.getMessage());
            }
        }
        return store;
    }

    /** RAG 顾问：每次提问自动检索 Top5 且相似度 >= 0.5 的片段注入 Prompt */
    @Bean
    public QuestionAnswerAdvisor questionAnswerAdvisor(VectorStore vectorStore) {
        return QuestionAnswerAdvisor.builder(vectorStore)
                .searchRequest(SearchRequest.builder()
                        .topK(5)
                        .similarityThreshold(0.5)
                        .build())
                .build();
    }
}
