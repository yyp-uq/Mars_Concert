package com.mars.concert.common.aspect;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mars.concert.common.annotation.OperationLog;
import com.mars.concert.common.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * 操作日志切面（手册 §11.2）：环绕通知采集模块/动作/耗时/成败/操作人/业务id，
 * 线程池异步落库，不影响主接口性能；落库失败静默，绝不影响业务。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class OperationLogAspect {

    private final OperationLogMapper logMapper;
    private final ObjectMapper objectMapper;
    private final SpelExpressionParser parser = new SpelExpressionParser();

    /** 异步落库线程池（学习期固定2线程足够） */
    private final Executor executor = Executors.newFixedThreadPool(2);

    @Around("@annotation(opLog)")
    public Object around(ProceedingJoinPoint pjp, OperationLog opLog) throws Throwable {
        long start = System.currentTimeMillis();
        Object result;
        String error = null;
        try {
            result = pjp.proceed();
            return result;
        } catch (Throwable t) {
            error = t.getMessage();
            throw t;
        } finally {
            long cost = System.currentTimeMillis() - start;
            SysOperationLog entity = new SysOperationLog();
            entity.setModule(opLog.module());
            entity.setAction(opLog.action());
            entity.setCostMs((int) cost);
            entity.setSuccess(error == null ? 1 : 0);
            entity.setErrorMsg(error == null ? null
                    : error.substring(0, Math.min(error.length(), 500)));
            entity.setOperator(Optional.ofNullable(UserContext.getUserId())
                    .map(String::valueOf).orElse("anonymous"));
            try {
                String params = objectMapper.writeValueAsString(pjp.getArgs());
                entity.setParams(params.length() > 2000 ? params.substring(0, 2000) : params);
            } catch (Exception ignored) {
                // 入参序列化失败不记录 params
            }
            if (!opLog.bizIdSpel().isBlank()) {
                try {
                    EvaluationContext ctx = new StandardEvaluationContext();
                    String[] names = ((MethodSignature) pjp.getSignature()).getParameterNames();
                    Object[] args = pjp.getArgs();
                    if (names != null) {
                        for (int i = 0; i < names.length; i++) ctx.setVariable(names[i], args[i]);
                    }
                    entity.setBizId(parser.parseExpression(opLog.bizIdSpel()).getValue(ctx, String.class));
                } catch (Exception ignored) {
                    // SpEL 解析失败不落 bizId
                }
            }
            executor.execute(() -> {
                try {
                    logMapper.insert(entity);
                } catch (Exception e) {
                    log.warn("操作日志落库失败 module={} action={}", entity.getModule(), entity.getAction(), e);
                }
            });
        }
    }
}
