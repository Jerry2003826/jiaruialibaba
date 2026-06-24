package com.example.agentdemo.workflow;

record WorkflowNodeRunOptions(int retryCount, long timeoutMs) {

    static WorkflowNodeRunOptions from(WorkflowNode node) {
        int retryCount = configInt(node, "retryCount", 0);
        long timeoutMs = configLong(node, "timeoutMs", 0);
        return new WorkflowNodeRunOptions(retryCount, timeoutMs);
    }

    boolean retryOrTimeoutConfigured() {
        return retryCount > 0 || timeoutMs > 0;
    }

    private static int configInt(WorkflowNode node, String key, int defaultValue) {
        Object value = node.config().get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.intValue());
        }
        if (value != null) {
            try {
                return Math.max(0, Integer.parseInt(String.valueOf(value)));
            }
            catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    private static long configLong(WorkflowNode node, String key, long defaultValue) {
        Object value = node.config().get(key);
        if (value instanceof Number number) {
            return Math.max(0, number.longValue());
        }
        if (value != null) {
            try {
                return Math.max(0, Long.parseLong(String.valueOf(value)));
            }
            catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

}
