package com.example.agentdemo.trace;

/**
 * Thread-local carrier for the app that originated the current run, so {@link TraceService} can
 * tag a run with its {@code app_id} without every runtime service needing to thread the id
 * through. The app runtime sets this around a synchronous delegate call (or before creating a run
 * on the request thread for streaming), then clears it in a finally block.
 */
public final class RunContext {

    private static final ThreadLocal<String> CURRENT_APP_ID = new ThreadLocal<>();

    private RunContext() {
    }

    public static void setAppId(String appId) {
        CURRENT_APP_ID.set(appId);
    }

    public static String currentAppId() {
        return CURRENT_APP_ID.get();
    }

    public static void clear() {
        CURRENT_APP_ID.remove();
    }

}
