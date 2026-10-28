package com.mars.ai.rag;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库管理接口（学习期不做权限，生产应限制管理员 content:ai:ingest 权限）。
 */
@RestController
@RequestMapping("/api/v1/ai/admin/knowledge")
public class KnowledgeAdminController {

    private final KnowledgeIngestor ingestor;
    private final SimpleVectorStore vectorStore;

    public KnowledgeAdminController(KnowledgeIngestor ingestor, SimpleVectorStore vectorStore) {
        this.ingestor = ingestor;
        this.vectorStore = vectorStore;
    }

    /** 灌入一段文本（自动切片、向量化、落盘） */
    @PostMapping("/ingest-text")
    public Map<String, Object> ingestText(@Valid @RequestBody IngestDTO dto) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("source", dto.getSource());
        metadata.put("category", dto.getCategory() == null ? "faq" : dto.getCategory());
        Document doc = new Document(dto.getContent(), metadata);
        return result(ingestor.ingestDocuments(List.of(doc)));
    }

    /** 全量灌入 ct_faq 表 */
    @PostMapping("/ingest-faq")
    public Map<String, Object> ingestFaq() {
        return result(ingestor.ingestDocuments(ingestor.faqDocuments()));
    }

    /** 统计向量库中可检索的片段数 */
    @GetMapping("/count")
    public Map<String, Object> count() {
        int size = vectorStore.similaritySearch(SearchRequest.builder()
                .query("火星演唱会")
                .topK(10000)
                .similarityThresholdAll()
                .build()).size();
        return result(size);
    }

    private Map<String, Object> result(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", 0);
        body.put("message", "success");
        body.put("data", data);
        return body;
    }

    /** 文本灌库入参 */
    @Data
    public static class IngestDTO {
        /** 来源标识，如「火星演唱会退票政策-2025」 */
        @NotBlank(message = "source 不能为空")
        private String source;
        /** refund / ticket / entry / faq */
        private String category;
        @NotBlank(message = "content 不能为空")
        private String content;
    }
}
