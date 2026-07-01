package com.example.agentdemo.audit;

import com.example.agentdemo.common.BusinessException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.Ordered;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;

/**
 * Records an audit row for every {@link Audited} method invocation. Ordered at highest precedence
 * so it wraps the transaction interceptor: a success is only recorded once the audited transaction
 * has committed, and a failure is recorded (in the audit service's own transaction) even after the
 * audited transaction has rolled back.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class AuditAspect {

    private static final Logger log = LoggerFactory.getLogger(AuditAspect.class);

    private final AuditService auditService;
    private final ExpressionParser expressionParser = new SpelExpressionParser();
    private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

    public AuditAspect(AuditService auditService) {
        this.auditService = auditService;
    }

    @Around("@annotation(com.example.agentdemo.audit.Audited)")
    public Object audit(ProceedingJoinPoint joinPoint) throws Throwable {
        Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
        Audited audited = method.getAnnotation(Audited.class);
        if (audited == null) {
            return joinPoint.proceed();
        }
        try {
            Object result = joinPoint.proceed();
            auditService.recordSuccess(audited.action(), audited.resourceType(),
                    resolveResourceId(joinPoint, method, audited, result));
            return result;
        }
        catch (BusinessException ex) {
            auditService.recordFailure(audited.action(), audited.resourceType(),
                    resolveResourceId(joinPoint, method, audited, null), ex.getCode());
            throw ex;
        }
        catch (Throwable ex) {
            auditService.recordFailure(audited.action(), audited.resourceType(),
                    resolveResourceId(joinPoint, method, audited, null), "INTERNAL_ERROR");
            throw ex;
        }
    }

    private String resolveResourceId(ProceedingJoinPoint joinPoint, Method method, Audited audited, Object result) {
        String expression = audited.resourceId();
        if (!StringUtils.hasText(expression)) {
            return null;
        }
        try {
            MethodBasedEvaluationContext context = new MethodBasedEvaluationContext(joinPoint.getTarget(), method,
                    joinPoint.getArgs(), parameterNameDiscoverer);
            context.setVariable("result", result);
            Object value = expressionParser.parseExpression(expression).getValue((EvaluationContext) context);
            return value == null ? null : String.valueOf(value);
        }
        catch (RuntimeException ex) {
            log.warn("Failed to resolve audit resourceId expression '{}' for action {}", expression,
                    audited.action(), ex);
            return null;
        }
    }

}
