package com.mars.ai.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.ai.config.AiProperties;
import com.mars.ai.tools.dto.OrderBriefVO;
import com.mars.ai.tools.dto.OrderDetailVO;
import com.mars.ai.tools.dto.RefundResultVO;
import com.mars.ai.tools.dto.TierStockVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Function Calling 工具集：查订单、查余票、申请退款。
 * 安全护栏：
 * 1. userId 只从 ToolContext（网关下推的登录态）取，绝不作为模型可见参数；
 * 2. 下游内部接口强制按 userId 过滤，订单不属于本人会被拒绝；
 * 3. 退款首轮只生成一次性 confirmToken，不执行资金操作。
 * 所有 HTTP 调用超时 5 秒。
 */
@Component
public class OrderTools {

    private static final Logger log = LoggerFactory.getLogger(OrderTools.class);

    private static final String REFUND_CONFIRM_PREFIX = "mars:ai:refund:confirm:";

    private final AiProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OrderTools(AiProperties properties, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.properties = properties;
        this.redis = redis;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /** 查当前登录用户的订单列表 */
    @Tool(description = "查询当前登录用户的演唱会订单列表，可按状态过滤。status 可选值：WAIT_PAY 待支付、PAID 已支付、TICKETED 已出票、REFUNDING 退款中、REFUNDED 已退款、CANCELLED 已取消；传空字符串表示全部")
    public List<OrderBriefVO> listMyOrders(String status, ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        URI uri = UriComponentsBuilder.fromUriString(properties.getServices().getOrderUrl())
                .path("/internal/order/list")
                .queryParam("userId", userId)
                .queryParamIfPresent("status",
                        StringUtils.hasText(status) ? java.util.Optional.of(status) : java.util.Optional.empty())
                .build().encode().toUri();
        JsonNode data = getJson(uri);
        return objectMapper.convertValue(data, new TypeReference<List<OrderBriefVO>>() {
        });
    }

    /** 按订单号查详情（强制校验归属） */
    @Tool(description = "根据订单号查询当前登录用户的订单详情，包括支付状态、金额、票档明细与座位号。只能查询本人的订单")
    public OrderDetailVO getOrderDetail(String orderNo, ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        URI uri = UriComponentsBuilder.fromUriString(properties.getServices().getOrderUrl())
                .path("/internal/order/detail")
                .queryParam("orderNo", orderNo)
                .queryParam("userId", userId)
                .build().encode().toUri();
        JsonNode data = getJson(uri);
        return objectMapper.convertValue(data, OrderDetailVO.class);
    }

    /** 查场次各票档余票 */
    @Tool(description = "查询指定演唱会场次（eventId）各票价档位的剩余可售票数量")
    public List<TierStockVO> getEventStock(Long eventId) {
        URI uri = UriComponentsBuilder.fromUriString(properties.getServices().getShowUrl())
                .path("/internal/show/events/{eventId}/tiers/stock")
                .buildAndExpand(eventId).encode().toUri();
        JsonNode data = getJson(uri);
        return objectMapper.convertValue(data, new TypeReference<List<TierStockVO>>() {
        });
    }

    /** 申请退款：首轮只要求确认，确认后才真正调用订单服务 */
    @Tool(description = "对指定订单发起退款申请。首次调用只返回确认提示与 confirmToken，不会真正退款；只有用户已明确确认（refundConfirmed=true）时才提交退款")
    public RefundResultVO applyRefund(String orderNo, ToolContext toolContext) {
        Long userId = currentUserId(toolContext);
        boolean confirmed = Boolean.TRUE.equals(toolContext.getContext().get("refundConfirmed"));

        String key = REFUND_CONFIRM_PREFIX + userId + ":" + orderNo;
        if (!confirmed) {
            String token = UUID.randomUUID().toString();
            redis.opsForValue().set(key, token,
                    Duration.ofMinutes(properties.getRefundConfirmTtlMinutes()));
            ConfirmCollector collector = (ConfirmCollector) toolContext.getContext().get("confirmCollector");
            if (collector != null) collector.set(orderNo, token);
            return new RefundResultVO(true, orderNo,
                    "退款需要您明确确认，请回复「确认退款」后再为您办理", token, null);
        }

        // 已确认：调用订单服务状态机迁移到 REFUNDING，并作废 token
        URI uri = UriComponentsBuilder.fromUriString(properties.getServices().getOrderUrl())
                .path("/internal/order/refund-apply")
                .queryParam("orderNo", orderNo)
                .queryParam("userId", userId)
                .build().encode().toUri();
        JsonNode data = postJson(uri);
        redis.delete(key);
        log.info("AI 发起退款 orderNo={} userId={} result={}", orderNo, userId, data);
        return new RefundResultVO(false, orderNo, "退款申请已受理，将按退票政策原路退回",
                null, data.path("status").asText("REFUNDING"));
    }

    /** userId 只能来自登录态，模型无法伪造 */
    private Long currentUserId(ToolContext toolContext) {
        Object value = toolContext == null || toolContext.getContext() == null
                ? null : toolContext.getContext().get("userId");
        if (value == null) throw new IllegalStateException("缺少登录用户信息");
        return Long.valueOf(String.valueOf(value));
    }

    private JsonNode getJson(URI uri) {
        JsonNode root = restClient.get().uri(uri).retrieve().body(JsonNode.class);
        return unwrap(root);
    }

    private JsonNode postJson(URI uri) {
        JsonNode root = restClient.post().uri(uri).retrieve().body(JsonNode.class);
        return unwrap(root);
    }

    /** 解析下游统一响应 Result{code,message,data} */
    private JsonNode unwrap(JsonNode root) {
        if (root == null) throw new IllegalStateException("下游服务无响应");
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException(root.path("message").asText("下游服务调用失败"));
        }
        return root.path("data");
    }
}
