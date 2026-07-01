package com.example.agentdemo.audit;

/**
 * Identifies who performed an audited action.
 */
public enum AuditActorType {

    /** A human/operator authenticated with a console JWT. */
    CONSOLE_JWT,

    /** A business integration authenticated with a runtime app API key. */
    APP_API_KEY,

    /** An internal, unauthenticated system process. */
    SYSTEM

}
