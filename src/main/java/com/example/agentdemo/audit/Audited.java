package com.example.agentdemo.audit;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a service method as an audited management action. {@code AuditAspect} records one audit
 * row per invocation with the resolved actor, the {@link #action()} / {@link #resourceType()} and
 * the {@link #resourceId()} evaluated as a SpEL expression over the method arguments (and, on
 * success, {@code #result}). Both success and failure outcomes are recorded.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {

    /** Stable action name, e.g. {@code workflow.publish}. */
    String action();

    /** Resource category, e.g. {@code workflow}, {@code document}, {@code app}. */
    String resourceType();

    /**
     * SpEL expression resolving the resource id from method arguments (bound by name and as
     * {@code #a0..#aN}) and, on success, the return value as {@code #result}. Empty means no id.
     */
    String resourceId() default "";

}
