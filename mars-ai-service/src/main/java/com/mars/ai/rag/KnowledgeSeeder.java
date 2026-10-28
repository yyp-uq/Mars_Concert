package com.mars.ai.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 首次启动灌入「退票政策」长文 + ct_faq 全量数据。
 * 用标志文件避免每次重启重复灌库；任何失败只打日志，不阻断服务启动。
 */
@Component
public class KnowledgeSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSeeder.class);

    private static final String FLAG_FILE = "data/.faq-seeded";

    /** 手册 §6.3 的真实退票规则 */
    private static final String REFUND_POLICY = """
            《火星演唱会2025退票政策》
            1. 开票后7天内且距演出30天以上退票，免手续费；
            2. 距演出10-30天退票，收取票价20%手续费；
            3. 距演出48小时-10天退票，收取50%手续费；
            4. 演出前48小时内不支持退票；
            5. 电子票已核销（已入场）不支持退票；
            6. 退票原路退回，1-7个工作日到账。
            """;

    private final KnowledgeIngestor ingestor;

    public KnowledgeSeeder(KnowledgeIngestor ingestor) {
        this.ingestor = ingestor;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            File flag = new File(FLAG_FILE);
            if (flag.exists()) {
                log.info("知识库已初始化过，跳过灌库");
                return;
            }

            List<Document> documents = new ArrayList<>();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("source", "火星演唱会2025退票政策");
            metadata.put("category", "refund");
            documents.add(new Document(REFUND_POLICY, metadata));
            documents.addAll(ingestor.faqDocuments());

            int chunks = ingestor.ingestDocuments(documents);
            File parent = flag.getParentFile();
            if (parent != null) parent.mkdirs();
            if (flag.createNewFile()) {
                log.info("知识库首次灌入完成：{} 个片段", chunks);
            }
        } catch (Exception e) {
            log.warn("知识库首次灌入失败（不影响启动，请确认 DASHSCOPE_API_KEY 与 ct_faq 表）：{}", e.getMessage());
        }
    }
}
