package com.example.agentdemo.usage;

/**
 * Aggregated token usage for a run.
 *
 * @param runId            the run id
 * @param provider         provider name (from the first record; usually one provider per run)
 * @param model            model name (from the first record)
 * @param promptTokens     summed prompt tokens
 * @param completionTokens summed completion tokens
 * @param totalTokens      summed total tokens
 * @param calls            number of model calls recorded
 */
public record UsageSummaryResponse(String runId, String provider, String model, int promptTokens,
        int completionTokens, int totalTokens, int calls) {
}
