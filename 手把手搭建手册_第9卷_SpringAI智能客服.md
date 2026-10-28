# 手把手搭建手册（第 9 卷 · 终卷）
## Spring AI 智能客服：ChatClient、SSE 流式、RAG 知识库、Function Calling、ChatMemory

> 前置：第 1~8 卷。本卷为火星演唱会平台接入一个真正能用的 AI 客服「火星小助手」：
> 能流式打字回复、记得多轮上下文、基于平台 FAQ/退票政策做 **RAG 检索增强**（不胡编）、通过 **Function Calling** 实时查订单/查余票/发起退款，并对敏感操作做安全护栏。
> 基于 **Spring AI 1.0.0（已 GA）** + JDK17 + Boot 3.3.5。

---

## 目录
1. [AI 客服整体架构与核心概念](#1-ai-客服整体架构与核心概念)
2. [依赖与版本、接入大模型](#2-依赖与版本接入大模型)
3. [第一个 ChatClient 对话](#3-第一个-chatclient-对话)
4. [SSE 流式输出 + 前端打字机](#4-sse-流式输出--前端打字机)
5. [ChatMemory 多轮记忆（Redis）](#5-chatmemory-多轮记忆redis)
6. [RAG：把 FAQ/政策文档灌进向量库](#6-rag把-faq政策文档灌进向量库)
7. [RAG：检索增强问答（QuestionAnswerAdvisor）](#7-rag检索增强问答questionansweradvisor)
8. [Function Calling：让模型调用真实业务](#8-function-calling让模型调用真实业务)
9. [工具的越权防护与二次确认（安全护栏）](#9-工具的越权防护与二次确认安全护栏)
10. [系统提示词、结构化输出与客服边界](#10-系统提示词结构化输出与客服边界)
11. [整合：一个完整的客服 Service](#11-整合一个完整的客服-service)
12. [密钥管理、可观测、异常处理](#12-密钥管理可观测异常处理)
13. [排错表与验收 Checklist](#13-排错表与验收-checklist)
14. [全套九卷学习路线回顾](#14-全套九卷学习路线回顾)

---

## 1. AI 客服整体架构与核心概念

```
用户(已登录) ──SSE──> ai-service (9009)
                        │
                        ├─ ChatClient（编排大脑）
                        │     ├─ SystemPrompt（人设/边界）
                        │     ├─ MessageChatMemoryAdvisor（多轮记忆，Redis）
                        │     ├─ QuestionAnswerAdvisor（RAG 检索 FAQ/政策）
                        │     └─ @Tool 工具（查订单/余票/退款）
                        │
                        ├─ EmbeddingModel ──> 文本向量化
                        ├─ VectorStore ──> 相似度检索（学习用内存，生产用 Redis/PgVector）
                        └─ ChatModel（OpenAI 兼容：通义 qwen-plus 等）
```

必懂 5 个概念：
- **ChatModel / ChatClient**：ChatModel 是对大模型的底层抽象；ChatClient 是 fluent 风格的高层编排 API。
- **Prompt / Message**：SystemMessage（人设规则）、UserMessage（用户）、AssistantMessage（AI）、ToolContextMessage。
- **Embedding / VectorStore**：把文本变成浮点向量，按「语义相似度」检索（不是关键词匹配），这是 RAG 的基础。
- **RAG（检索增强生成）**：先从你自己的知识库里检索相关片段，塞进 Prompt，让模型「看着资料回答」，大幅减少幻觉。
- **Function Calling（Tool Calling）**：模型判断需要实时数据/执行动作时，按约定格式请求调用你的 Java 方法，你执行后把结果回传给它继续作答。

---

## 2. 依赖与版本、接入大模型

### 2.1 依赖管理
在（单体 `pom.xml` 的 dependencyManagement，或第 8 卷父 pom）加入 Spring AI BOM：
```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.springframework.ai</groupId>
      <artifactId>spring-ai-bom</artifactId>
      <version>1.0.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>
<!-- Spring AI 里程碑仓库（1.0.0 已在 Maven Central，若拉不到再加仓库）-->
<repositories>
  <repository>
    <id>spring-milestones</id>
    <url>https://repo.spring.io/milestone</url>
  </repository>
</repositories>
```

### 2.2 Starter（OpenAI 兼容方式，最通用）
用 OpenAI starter 对接任何「OpenAI 兼容接口」，学习推荐**阿里云通义 DashScope 兼容模式**（国内直连、无需代理）：
```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-openai</artifactId>
</dependency>
```
> 也可加 `spring-ai-starter-vector-store-redis`（第 6.4 节生产向量库）。

### 2.3 application.yml
```yaml
spring:
  ai:
    openai:
      api-key: ${DASHSCOPE_API_KEY}          # 从环境变量读，不要写进代码/仓库
      base-url: https://dashscope.aliyuncs.com/compatible-mode
      chat:
        options:
          model: qwen-plus
          temperature: 0.3                     # 客服场景调低，求稳
      embedding:
        options:
          model: text-embedding-v3            # 通义向量模型（1024维）
```
获取 Key：阿里云 → 百炼控制台 → API-KEY 管理，新建一个，设到环境变量：
```powershell
setx DASHSCOPE_API_KEY "sk-你的key"
```
（IDEA 里也可在运行配置的 Environment variables 设置。）

> 想接真正的 OpenAI：去掉 base-url、model 改 gpt-4o-mini、api-key 用 sk-...，并确保网络可达。接口层代码完全一样——这正是 Spring AI 抽象的价值。

---

## 3. 第一个 ChatClient 对话

路径：`ai/config/ChatClientConfig.java`
```java
@Configuration
public class ChatClientConfig {

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
}
```

最朴素的调用（先打通连通性）：
```java
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class ChatPingController {
    private final ChatClient chatClient;

    @GetMapping("/ping")
    public String ping(@RequestParam(defaultValue = "你好") String q) {
        return chatClient.prompt().user(q).call().content();
    }
}
```
GET http://localhost:8080/api/v1/ai/ping?q=你是谁 → 应返回一段客服式自我介绍。**能收到回复说明 Key/网络/模型全通**，再往下做。

---

## 4. SSE 流式输出 + 前端打字机

模型逐 token 生成，用 SSE 推给前端实现打字机效果。本项目是 Spring MVC（Tomcat），用 `SseEmitter` 桥接 Spring AI 的响应式流。

路径：`ai/web/ChatSseController.java`
```java
@RestController
@RequestMapping("/api/v1/ai")
@RequiredArgsConstructor
public class ChatSseController {
    private final CustomerSupportService supportService;

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatDTO dto, @CurrentUser Long userId) {
        SseEmitter emitter = new SseEmitter(120_000L);
        supportService.streamChat(userId, dto.getConversationId(), dto.getMessage(), emitter);
        return emitter;
    }
}
```

`ChatDTO`：`{ conversationId, message }`。

Service 内订阅流（完整版在第 11 节给出含记忆/RAG/工具的），此处先演示流式发送骨架：
```java
public void streamChat(Long userId, String convId, String userMsg, SseEmitter emitter) {
    Flux<String> tokens = chatClient.prompt()
            .user(userMsg)
            .stream()
            .content();                       // 每个元素是一小段文字

    tokens.subscribe(
        token -> {
            try { emitter.send(SseEmitter.event().name("delta").data(token)); }
            catch (IOException e) { emitter.completeWithError(e); }
        },
        err -> emitter.completeWithError(err),
        ()    -> emitter.complete()
    );
}
```

### 4.1 前端聊天页
新建 `src/main/resources/static/mock/ai-chat.html`（蓝白风格，复用站点审美）：
```html
<div id="messages"></div>
<div class="composer">
  <input id="convId" type="hidden" />
  <input id="input" placeholder="问我：怎么退票？/ 我的订单出票了吗？" />
  <button onclick="send()">发送</button>
</div>
<script src="/js/api.js"></script>
<script>
const convId = crypto.randomUUID();
function append(role, text){
  const div = document.createElement('div');
  div.className = 'msg ' + role; div.textContent = text;
  document.getElementById('messages').appendChild(div);
}
async function send(){
  const input = document.getElementById('input');
  const content = input.value.trim(); if(!content) return;
  append('user', content); input.value='';
  const bubble = document.createElement('div');
  bubble.className='msg assistant'; document.getElementById('messages').appendChild(bubble);

  const resp = await fetch('/api/v1/ai/chat/stream', {
    method:'POST',
    headers:{ 'Content-Type':'application/json', 'Authorization':'Bearer '+Api.token() },
    body: JSON.stringify({ conversationId: convId, message: content })
  });
  const reader = resp.body.getReader();
  const decoder = new TextDecoder();
  let buf = '';
  while(true){
    const { value, done } = await reader.read(); if(done) break;
    buf += decoder.decode(value, {stream:true});
    const parts = buf.split('\n\n'); buf = parts.pop();
    for(const p of parts){
      const line = p.split('\n').find(l => l.startsWith('data:'));
      if(line){ bubble.textContent += line.slice(5).replace(/^ /,''); }
    }
  }
}
</script>
```
> SSE 规范：事件之间用 `\n\n` 分隔，数据在 `data:` 行。EventSource 只支持 GET，POST + Bearer 用 `fetch` 读取流（如上），兼容性最好。

---

## 5. ChatMemory 多轮记忆（Redis）

让模型记住「上文」。Spring AI 1.0 用 `ChatMemory` + `MessageChatMemoryAdvisor`。默认内存库重启即失，我们用 **Redis 持久化仓库**（也可自己实现 `ChatMemoryRepository`）。

### 5.1 依赖
```xml
<dependency>
  <groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-model-chat-memory-repository-jdbc</artifactId>
</dependency>
```
> 最简单可先用 JDBC 仓库（建表脚本由该 starter 提供）；想用 Redis 则自定义 `ChatMemoryRepository` 基于 `StringRedisTemplate`，key 用 `mars:ai:chat:{conversationId}`，value 存消息 JSON list。下面给 Redis 实现，贴合前几卷技术栈。

### 5.2 Redis 版 ChatMemoryRepository
路径：`ai/memory/RedisChatMemoryRepository.java`
```java
@Component
@RequiredArgsConstructor
public class RedisChatMemoryRepository implements ChatMemoryRepository {
    private final StringRedisTemplate redis;
    private static final String PREFIX = "mars:ai:chat:";

    @Override
    public List<Message> findByConversationId(String conversationId) {
        String json = redis.opsForValue().get(PREFIX + conversationId);
        if (!StringUtils.hasText(json)) return new ArrayList<>();
        List<StoredMessage> list = JSON.parseArray(json, StoredMessage.class);
        List<Message> msgs = new ArrayList<>();
        for (StoredMessage s : list) {
            switch (s.getType()) {
                case USER -> msgs.add(new UserMessage(s.getTextContent()));
                case ASSISTANT -> msgs.add(new AssistantMessage(s.getTextContent()));
                case SYSTEM -> msgs.add(new SystemMessage(s.getTextContent()));
                default -> { }
            }
        }
        return msgs;
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        List<StoredMessage> stored = messages.stream()
            .map(m -> new StoredMessage(m.getMessageType(), m.getText()))
            .toList();
        redis.opsForValue().set(PREFIX + conversationId, JSON.toJSONString(stored), Duration.ofHours(12));
    }

    @Data @AllArgsConstructor
    static class StoredMessage {
        private MessageType type;
        private String textContent;
    }
}
```

### 5.3 装配 ChatMemory（滑动窗口，保留最近 10 轮）
```java
@Bean
public ChatMemory chatMemory(ChatMemoryRepository repo) {
    return MessageWindowChatMemory.builder()
            .chatMemoryRepository(repo)
            .maxMessages(20)   // 10 轮 = 20 条（user+assistant）
            .build();
}
```

### 5.4 会话隔离（关键）
conversationId 由后端按用户生成/校验，绝不直接信任前端传入，避免读到别人对话：
```java
String convId = "u" + userId + ":" + userProvidedConvId;   // 强制加用户前缀
```
顾问装配见第 11 节：`MessageChatMemoryAdvisor.builder(chatMemory).build()`，并在 `.advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))` 传入会话 ID。

---

## 6. RAG：把 FAQ/政策文档灌进向量库

目标：模型回答「退票要扣多少手续费」「儿童要票吗」时，依据平台真实文档。

### 6.1 学习期向量库：SimpleVectorStore（内存 + 落 JSON）
生产再换 Redis/PgVector（接口 `VectorStore` 一致，代码几乎不改）。

```java
@Bean
public VectorStore vectorStore(EmbeddingModel embeddingModel) throws IOException {
    SimpleVectorStore store = new SimpleVectorStore(embeddingModel);
    File file = new File("data/vectorstore-faq.json");
    if (file.exists()) store.load(FileSystemResource(file));  // 重启后加载
    return store;
}
```

### 6.2 ETL：读取 → 切片 → 向量化 → 入库
Spring AI 的 ETL 管道：`DocumentReader` → `TextSplitter` → `EmbeddingModel` → `VectorStore`。

路径：`ai/rag/KnowledgeAdminController.java`（仅管理员 `content:ai:ingest` 权限）
```java
@RestController
@RequestMapping("/api/v1/ai/admin/knowledge")
@RequiredArgsConstructor
public class KnowledgeAdminController {
    private final VectorStore vectorStore;

    @PostMapping("/ingest-text")
    public Result<Integer> ingest(@RequestBody IngestDTO dto) {
        // 1. 构造成文档（带来源元数据，便于溯源/去重）
        Document doc = new Document(dto.getContent(),
                Map.of("source", dto.getSource(), "category", dto.getCategory()));

        // 2. 切片：按 token 分块，带重叠避免切断语义
        TokenTextSplitter splitter = new TokenTextSplitter(
                300,   // 默认块大小（token）
                60,    // 相邻重叠
                5,     // 最大块数
                10000, // 单段最大字符
                true);
        List<Document> chunks = splitter.split(doc);

        // 3. 向量化并入库（内部自动调用 embedding model）
        vectorStore.add(chunks);

        // SimpleVectorStore 手动持久化；Redis/PgVector 无需
        if (vectorStore instanceof SimpleVectorStore svs) {
            try { svs.save(new File("data/vectorstore-faq.json")); }
            catch (IOException ignored) {}
        }
        return Result.success(chunks.size());
    }

    @Data
    public static class IngestDTO {
        private String source;      // 如「火星演唱会退票政策-2025」
        private String category;    // refund / ticket / entry / faq
        private String content;
    }
}
```

### 6.3 灌入真实知识（用第 1 卷 ct_faq + 政策长文）
也可直接从数据库 FAQ 表批量导入（写一个启动任务/管理接口）：
```java
public void ingestFaqList(List<Faq> faqs) {
    List<Document> docs = faqs.stream()
        .map(f -> new Document(
            "问：" + f.getQuestion() + "\n答：" + f.getAnswer(),
            Map.of("source", "FAQ#" + f.getId(), "category", f.getCategory())))
        .toList();
    List<Document> chunks = new TokenTextSplitter(300, 60, 5, 10000, true).split(docs);
    vectorStore.add(chunks);
}
```
退票政策示例文本（真实规则，非占位）：
```
《火星演唱会2025退票政策》
1. 开票后7天内且距演出30天以上退票，免手续费；
2. 距演出10-30天退票，收取票价20%手续费；
3. 距演出48小时-10天退票，收取50%手续费；
4. 演出前48小时内不支持退票；
5. 电子票已核销（已入场）不支持退票；
6. 退票原路退回，1-7个工作日到账。
```
调用 `POST /api/v1/ai/admin/knowledge/ingest-text` 逐条导入，或从 FAQ 表一键灌库。

### 6.4 生产向量库（Redis Stack / PgVector）
只需换 starter 与 Bean。例如 Redis：
```xml
<dependency><groupId>org.springframework.ai</groupId>
  <artifactId>spring-ai-starter-vector-store-redis</artifactId></dependency>
```
```yaml
spring.ai.vectorstore.redis:
  index: faq_index
  prefix: "mars:vec:"
```
`VectorStore` 注入点、检索、Advisor 代码全部不变。

---

## 7. RAG：检索增强问答（QuestionAnswerAdvisor）

无需手动「检索→拼 prompt」，Spring AI 提供 `QuestionAnswerAdvisor`，它在每次提问时自动：用用户问题做 embedding → 从 VectorStore 取 Top-K 相关片段 → 注入 Prompt。

```java
@Bean
public QuestionAnswerAdvisor qaAdvisor(VectorStore vectorStore) {
    SearchFilters filters = SearchFilters.builder().build();
    return QuestionAnswerAdvisor.builder(vectorStore)
            .searchParams(SearchRequest.builder()
                    .topK(5)
                    .similarityThreshold(0.5)   // 相似度门槛，过滤不相关
                    .build())
            .build();
}
```
使用（挂到 ChatClient）：
```java
String answer = chatClient.prompt()
        .user("演出前三天还能退票吗，扣多少钱")
        .advisors(qaAdvisor)
        .call().content();
```
模型会基于检索到的退票政策回答「收取50%手续费」，而不是凭空编一个比例。

带元数据过滤（只检索退票类）：
```java
SearchRequest.builder().topK(5)
    .filterExpression("category == 'refund'").build();
```

---

## 8. Function Calling：让模型调用真实业务

模型自己没有实时数据，也不能直接操作数据库。我们用 **`@Tool` 注解**（Spring AI 1.0 正式方式）暴露 Java 方法，模型在需要时自动决定调用哪个、传什么参数。

路径：`ai/tools/OrderTools.java`
```java
@Component
@RequiredArgsConstructor
public class OrderTools {

    private final OrderQueryService orderQueryService;     // 复用前几卷 Service
    private final EventQueryService eventQueryService;
    private final RefundService refundService;

    /** 查当前登录用户的订单列表 */
    @Tool(description = "查询当前登录用户的演唱会订单，可按状态过滤，状态可选 UNPAID/PAID/TICKETED/REFUNDED")
    public List<OrderToolVO> listMyOrders(String status, ToolContext ctx) {
        Long userId = (Long) ctx.getContext().get("userId");
        return orderQueryService.listBriefByUser(userId, status);
    }

    /** 根据订单号查订单与出票详情 */
    @Tool(description = "根据订单号查询订单详情、支付状态、电子票号与核销状态")
    public OrderDetailToolVO getOrderDetail(String orderNo, ToolContext ctx) {
        Long userId = (Long) ctx.getContext().get("userId");
        return orderQueryService.getDetailForAi(orderNo, userId); // 内部强制按userId过滤
    }

    /** 查某场演出各票档余票 */
    @Tool(description = "查询指定演唱会场次的各票价档位剩余可售票数量")
    public List<TierStockVO> getEventStock(Long eventId) {
        return eventQueryService.listTierStock(eventId);
    }

    /** 申请退款（资金操作，需用户已二次确认） */
    @Tool(description = "在用户已明确确认的前提下，对指定订单发起退款申请")
    public RefundResultVO applyRefund(String orderNo, ToolContext ctx) {
        Long userId = (Long) ctx.getContext().get("userId");
        Boolean confirmed = (Boolean) ctx.getContext().getOrDefault("refundConfirmed", false);
        if (!confirmed) {
            return RefundResultVO.needConfirm("退款需要您明确确认，请回复「确认退款」后再为您办理");
        }
        return refundService.applyForAi(orderNo, userId);
    }
}
```

### 8.1 关键点
- `description` 是给模型看的「工具说明书」，写清楚**何时用、参数含义**，模型据此选择。
- 方法参数会自动生成 JSON Schema 给模型；复杂参数用带字段注释的 DTO/record。
- `ToolContext` 用来传**不希望模型编造的运行时数据**（当前登录用户、是否已确认），比放进对话更安全。

### 8.2 注册工具并调用
```java
String answer = chatClient.prompt()
        .user("帮我看看我的票出票了没，订单号 M202507010001")
        .tools(orderTools)
        .toolContext(Map.of("userId", userId, "refundConfirmed", false))
        .call().content();
```
执行过程（可在日志看到）：模型识别需要查订单 → 请求调用 `getOrderDetail(M202507010001)` → Spring AI 反射执行你的方法 → 把返回的 VO 序列化回传 → 模型据此自然语言作答：「您的订单已出票，电子票号 TK…，状态为待入场」。

> VO 尽量字段精简、命名清晰，返回过多冗余字段会浪费 token 并干扰模型。

---

## 9. 工具的越权防护与二次确认（安全护栏）

AI 能触发真实操作，安全必须做在**你的代码里**，不能靠 Prompt 自觉。

1. **身份不来自模型参数，来自登录态**：userId 永远从 `@CurrentUser`/网关注入，经 ToolContext 传入。绝不让模型/用户直接指定 userId。
2. **数据归属校验**：所有查询/退款方法内部 `WHERE user_id = ?`，订单不属于本人直接抛错（复用第 2 卷防越权）。
3. **写操作幂等**：退款复用第 5 卷幂等键，避免模型重复调用导致重复退款。
4. **资金操作二次确认**：首轮 `refundConfirmed=false`，工具只返回「需要确认」，不真正退款；前端收到带 `needConfirm` 的事件时弹确认框；用户点「确认」后再次发起，后端校验最近一轮确为退款意图且带一次性 confirmToken，才置 `true` 执行。

用 confirmToken 防伪造（推荐）：
```java
// 首次：生成一次性 token 存 Redis：mars:ai:refund:confirm:{userId}:{orderNo}，TTL 5分钟
String token = UUID.randomUUID().toString();
redis.opsForValue().set("mars:ai:refund:confirm:"+userId+":"+orderNo, token, Duration.ofMinutes(5));
return RefundResultVO.needConfirm(orderNo, token);
```
用户确认时前端把 token 回传，Service 校验通过才执行并删除 token（SETNX/GET-AND-DELETE）。

5. **敏感词与越界**：复用第 6 卷 DFA 对用户输入和模型输出做敏感词过滤；SystemPrompt 限定话题。
6. **工具白名单/最小权限**：客服只暴露查询与退款申请，绝不暴露「改价、批量退票、查别人订单」等。
7. **限流**：复用 Sentinel 对 `/ai/**` 按用户限流，防止刷 token 烧钱。

---

## 10. 系统提示词、结构化输出与客服边界

### 10.1 结构化输出（让模型返回可控 JSON）
例如「咨询意图分类」用于路由人工/机器人：
```java
record SupportIntent(String category, String orderNo, boolean needHuman, double urgency) {}

SupportIntent intent = chatClient.prompt()
        .user(userMessage)
        .call()
        .entity(SupportIntent.class);   // Spring AI 自动要求模型输出 JSON 并转换
if (intent.needHuman()) { /* 转人工排队 */ }
```

### 10.2 Prompt 组织建议
- System：人设 + 硬性规则 + 边界 + 输出要求（一次性定义，见第 3 节）。
- 检索片段由 Advisor 自动追加，不要手写死。
- 给模型的工具返回里包含明确状态枚举，避免它猜状态。
- temperature 客服类 0.2~0.4；创意类才调高。

### 10.3 兜底与转人工
模型连续 2 次低置信、或用户明确说「人工」、或触发工具异常，返回固定话术并创建第 6 卷消息中心工单，接入人工客服。

---

## 11. 整合：一个完整的客服 Service

路径：`ai/service/CustomerSupportService.java`
```java
@Service
@RequiredArgsConstructor
public class CustomerSupportService {

    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final QuestionAnswerAdvisor qaAdvisor;
    private final OrderTools orderTools;
    private final StringRedisTemplate redis;

    public void streamChat(Long userId, String rawConvId, String userMsg,
                           String confirmToken, SseEmitter emitter) {
        // 1. 会话隔离
        String convId = "u" + userId + ":" + rawConvId;

        // 2. 二次确认校验（仅退款工具会用到）
        boolean refundConfirmed = validateRefundConfirm(userId, userMsg, confirmToken);

        // 3. 组装顾问：记忆 + RAG
        MessageChatMemoryAdvisor memoryAdvisor =
                MessageChatMemoryAdvisor.builder(chatMemory).build();

        Flux<String> flux = chatClient.prompt()
                .user(userMsg)
                .advisors(memoryAdvisor, qaAdvisor)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, convId))
                .tools(orderTools)
                .toolContext(Map.of(
                        "userId", userId,
                        "refundConfirmed", refundConfirmed))
                .stream()
                .content();

        flux.subscribe(
            token -> sendQuietly(emitter, SseEmitter.event().name("delta").data(token)),
            err -> {
                sendQuietly(emitter, SseEmitter.event().name("error")
                        .data("小助手开小差了，已为您转接人工客服"));
                emitter.complete();
            },
            emitter::complete
        );
    }

    private boolean validateRefundConfirm(Long userId, String msg, String token) {
        if (token == null) return false;
        // 从 msg/上下文定位订单号的简化做法：真实实现应在发起退款时绑定 orderNo
        String key = "mars:ai:refund:confirm:" + userId + ":pending";
        String saved = redis.opsForValue().get(key);
        if (token.equals(saved)) {
            redis.delete(key);
            return true;
        }
        return false;
    }

    private void sendQuietly(SseEmitter emitter, SseEventBuilder b){
        try { emitter.send(b); } catch (IOException ignored) {}
    }
}
```
控制器把 `confirmToken` 加入 ChatDTO（可选字段），普通对话传 null。这样一条链路同时具备：**人设 + 多轮记忆 + 知识库检索 + 实时工具调用 + 安全护栏 + 流式输出**。

---

## 12. 密钥管理、可观测、异常处理

- **密钥**：`api-key` 一律走环境变量/配置中心（第 8 卷 Nacos 可加密配置），禁止提交 git；可加 `.gitignore` 检查。
- **成本控制**：记录每次调用 token 用量。Spring AI 返回的 `Usage`（prompt/completion tokens）可落库或打 Micrometer 指标 `mars_ai_tokens_total`，在第 7 卷 Grafana 监控用量与费用。
- **超时与重试**：为 WebClient 设连接/读取超时；流式首 token 超时给兜底话术；对 429（限流）做退避。
- **缓存**：高频标准问题（FAQ 类、无个性化）可对「问题归一化后的答案」做 Redis 缓存，减少调用。
- **降级**：大模型不可用时，先命中 FAQ 精确匹配/关键词，再转人工，不要白屏。
- **日志**：记录 conversationId、userId、命中的工具名、检索到的文档 source（可溯源），但**不要记录完整 api-key**。
- **审计**：退款等工具调用落第 6 卷操作日志（谁、哪场对话、对哪张单、结果）。

---

## 13. 排错表与验收 Checklist

| 现象 | 原因/解决 |
|---|---|
| 401/Invalid API Key | Key 错或环境变量没生效（IDEA 运行配置需重启读取） |
| 连接超时 | base-url 错/需代理；DashScope 用国内地址无需代理 |
| model not found | 模型名拼错；通义用 qwen-plus、embedding 用 text-embedding-v3 |
| 向量维度不匹配报错 | 换 embedding 模型会改维度，需清空旧向量库重新灌库 |
| 工具不被调用 | @Tool 的 description 太模糊；方法不是 public；返回类型太复杂 |
| 工具里 userId 为空 | 没用 toolContext 传，或把 userId 当模型参数 |
| 模型乱答退票比例 | RAG 没检索到：检查是否已灌库、similarityThreshold 是否过高、切片是否合理 |
| 多轮记不住 | CONVERSATION_ID 每次变了；ChatMemory 顾问没加；窗口 maxMessages 太小 |
| SSE 前端收不到/一次性返回 | 没用流式读取；Nginx/网关需关闭缓冲（proxy_buffering off） |
| 跨域 | 在网关统一 CORS（第 8 卷），SSE 的 Content-Type 要 text/event-stream |
| 重复退款 | 缺二次确认/幂等键；confirmToken 必须一次性删除 |
| 读到别人对话 | convId 未按 userId 隔离 |

### 验收 Checklist
- [ ] `/ai/ping` 返回符合「火星小助手」人设
- [ ] 聊天页打字机流式输出正常，中途可看到逐字出现
- [ ] 连续提问「我上一句问的什么」能答对（多轮记忆），且 A 用户读不到 B 用户会话
- [ ] 灌入库后问退票手续费，答案与政策一致；问库中没有的内容不乱编、转人工
- [ ] 「我的订单出票了吗」触发 `getOrderDetail` 工具，返回真实订单数据
- [ ] 用户只能查到自己订单，伪造他人订单号被拒
- [ ] 「查某场余票」触发 getEventStock，数量与库存一致
- [ ] 说「退款」首轮只要求确认、不退款；确认（带 token）后才真正发起且幂等
- [ ] 大模型停用时有 FAQ/人工兜底，不白屏
- [ ] Grafana 能看到 AI 调用次数与 token 用量

---

## 14. 全套九卷学习路线回顾

1. **第 1 卷**：IDEA 手建工程、Spring Boot、FAQ CRUD、Knife4j、统一返回/异常、前端同源托管——跑通「请求-响应」闭环。
2. **第 2 卷**：会员/RBAC、Spring Security、JWT 双 Token、Redis 验证码、刷新旋转、防越权。
3. **第 3 卷**：演出/票档、订单状态机、服务端算价、幂等、MySQL 条件更新防超卖、事务。
4. **第 4 卷**：选座、Lua 原子锁、Redisson 看门狗、缓存三大问题、延迟队列双保险、策略模式。
5. **第 5 卷**：RabbitMQ 支付出票链路、手动 ACK、死信、本地消息表最终一致、回调验签、退款/核销/邮件。
6. **第 6 卷**：WebSocket 弹幕、敏感词、优惠券/积分、秒杀削峰、ES 搜索、MinIO、Excel、AOP 操作日志。
7. **第 7 卷**：Actuator/Prometheus/Grafana、traceId 透传、自研 Starter（自动装配）、多模块、单元/集成测试。
8. **第 8 卷**：Nacos、Gateway 鉴权、OpenFeign、Sentinel、Seata 分布式事务、全链路追踪——单体到微服务。
9. **第 9 卷（本卷）**：Spring AI 流式对话、RAG、Function Calling、记忆与安全护栏——AI 落地。

继续深入的方向：K8s 容器编排与 CI/CD、领域驱动设计（DDD）与读写分离（CQRS）、异地多活与压测、AI 的多模态（识图验票）与 Agent 编排。九卷已覆盖从「一行 Controller」到「可观测、可治理、具备 AI 能力的分布式系统」的主干，建议每卷都**亲手敲通再进下一卷**。祝火星演唱会顺利开唱！
