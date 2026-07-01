package com.example.agentdemo.usage;

import com.example.agentdemo.chat.TokenUsage;
import com.example.agentdemo.security.SecurityIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * Persists per-call token usage and aggregates it per run. Recording never throws into the caller
 * — usage accounting must not break a chat/run.
 */
@Service
public class UsageRecordingService {

    private static final Logger log = LoggerFactory.getLogger(UsageRecordingService.class);

    private final UsageRecordRepository usageRecordRepository;

    public UsageRecordingService(UsageRecordRepository usageRecordRepository) {
        this.usageRecordRepository = usageRecordRepository;
    }

    public void record(String runId, String appId, TokenUsage usage) {
        if (usage == null || !StringUtils.hasText(runId)) {
            return;
        }
        try {
            usageRecordRepository.save(new UsageRecordEntity(runId, appId, usage.provider(), usage.model(),
                    usage.promptTokens(), usage.completionTokens(), usage.totalTokens()));
        }
        catch (RuntimeException ex) {
            log.warn("Failed to record token usage for run {}", runId, ex);
        }
    }

    @Transactional(readOnly = true)
    public UsageSummaryResponse summarize(String runId) {
        List<UsageRecordEntity> records = usageRecordRepository.findByRunIdAndOwnerId(runId,
                SecurityIdentity.currentOwnerId());
        int prompt = 0;
        int completion = 0;
        int total = 0;
        String provider = null;
        String model = null;
        for (UsageRecordEntity record : records) {
            prompt += orZero(record.getPromptTokens());
            completion += orZero(record.getCompletionTokens());
            total += orZero(record.getTotalTokens());
            if (provider == null) {
                provider = record.getProvider();
                model = record.getModel();
            }
        }
        return new UsageSummaryResponse(runId, provider, model, prompt, completion, total, records.size());
    }

    private int orZero(Integer value) {
        return value == null ? 0 : value;
    }

}
