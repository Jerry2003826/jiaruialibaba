package com.example.agentdemo.app;

/**
 * The runtime shape of an application.
 */
public enum AppType {

    /** A conversational app driven by a system prompt, model and memory settings. */
    CHAT,

    /** An app bound to a published workflow definition/version. */
    WORKFLOW,

    /** An app using the tool-calling / assistant agent capabilities. */
    AGENT

}
