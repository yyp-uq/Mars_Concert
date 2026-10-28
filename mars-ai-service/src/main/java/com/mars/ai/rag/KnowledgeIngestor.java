package com.mars.ai.rag;

import com.mars.ai.config.ChatClientConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识入库通用逻辑：切片 → 向量化 → 写库 → 落盘。
 * 管理接口与启动灌库任务共用，避免两套口径。
 */
@Component
public class KnowledgeIngestor {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestor.class);

    private final SimpleVectorStore vectorStore;
    private final JdbcTemplate jdbcTemplate;

    public KnowledgeIngestor(SimpleVectorStore vectorStore, JdbcTemplate jdbcTemplate) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 切片并入库，返回片段数 */
    public int ingestDocuments(List<Document> documents) {
        if (documents == null || documents.isEmpty()) return 0;
        TokenTextSplitter splitter = new TokenTextSplitter(300, 60, 5, 10000, true);
        List<Document> chunks = splitter.split(documents);
        vectorStore.add(chunks);
        persist();
        return chunks.size();
    }

    /** 从第 1 卷 ct_faq 表读取上架 FAQ 并转成文档 */
    public List<Document> faqDocuments() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, category, question_en, question_zh, answer FROM ct_faq " +
                        "WHERE status = 1 AND deleted = 0 ORDER BY sort_no, id");
        List<Document> docs = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String questionZh = row.get("question_zh") == null ? null : String.valueOf(row.get("question_zh"));
            String questionEn = row.get("question_en") == null ? null : String.valueOf(row.get("question_en"));
            String question = StringUtils.hasText(questionZh) ? questionZh : questionEn;
            String answer = row.get("answer") == null ? "" : String.valueOf(row.get("answer"));
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "FAQ#" + row.get("id"));
            metadata.put("category", row.get("category") == null ? "GENERAL" : String.valueOf(row.get("category")));
            docs.add(new Document("问：" + question + "\n答：" + answer, metadata));
        }
        return docs;
    }

    /** SimpleVectorStore 是内存库，必须手动落盘，重启后由 Bean 初始化时 load */
    public void persist() {
        File file = new File(ChatClientConfig.VECTOR_STORE_FILE);
        File parent = file.getParentFile();
        if (parent != null) parent.mkdirs();
        try {
            vectorStore.save(file);
        } catch (Exception e) {
            log.warn("向量库落盘失败: {}", e.getMessage());
        }
    }
}
