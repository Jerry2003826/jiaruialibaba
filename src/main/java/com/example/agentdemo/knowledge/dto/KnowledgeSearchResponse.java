package com.example.agentdemo.knowledge.dto;

import com.example.agentdemo.knowledge.Citation;

import java.util.List;

/**
 * Knowledge-base search results as citations.
 */
public record KnowledgeSearchResponse(String kbId, String query, List<Citation> citations) {
}
