package com.example.agentdemo.knowledge;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Registers the default no-op {@link Reranker} (keeps retriever order, trims to {@code topK}) only
 * when no other {@link Reranker} bean is present, so a real reranker can override it.
 */
@Configuration
public class RerankerConfig {

    @Bean
    @ConditionalOnMissingBean(Reranker.class)
    Reranker noOpReranker() {
        return (query, candidates, topK) -> candidates.size() <= topK ? candidates : candidates.subList(0, topK);
    }

}
