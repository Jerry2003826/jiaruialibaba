package com.example.agentdemo.app;

/**
 * Lifecycle state of an application.
 */
public enum AppStatus {

    /** Editable, not runnable as a published runtime. */
    DRAFT,

    /** Has a published revision and can be invoked through the runtime API. */
    PUBLISHED,

    /** Retired but retained because run history references it. */
    ARCHIVED

}
