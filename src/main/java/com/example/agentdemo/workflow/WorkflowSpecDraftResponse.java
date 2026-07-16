package com.example.agentdemo.workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record WorkflowSpecDraftResponse(
        Status status,
        String summary,
        List<String> questions,
        List<Clarification> clarifications,
        Map<String, Object> spec,
        String generationPrompt) {

    public WorkflowSpecDraftResponse(Status status, String summary, List<String> questions, Map<String, Object> spec,
            String generationPrompt) {
        this(status, summary, questions, List.of(), spec, generationPrompt);
    }

    public WorkflowSpecDraftResponse {
        if (status == null) {
            status = Status.NEEDS_CLARIFICATION;
        }
        summary = summary == null ? "" : summary.trim();
        questions = questions == null ? List.of() : List.copyOf(questions);
        clarifications = normalizeClarifications(questions, clarifications);
        if (questions.isEmpty() && !clarifications.isEmpty()) {
            questions = clarifications.stream().map(Clarification::question).toList();
        }
        spec = spec == null ? Map.of() : Map.copyOf(spec);
        generationPrompt = generationPrompt == null ? "" : generationPrompt.trim();
    }

    public enum Status {
        NEEDS_CLARIFICATION,
        READY
    }

    public record Clarification(
            String question,
            List<String> options,
            String freeformPrompt) {

        public Clarification {
            question = question == null ? "" : question.trim();
            options = options == null ? List.of() : options.stream()
                    .filter(option -> option != null && !option.isBlank())
                    .map(String::trim)
                    .toList();
            freeformPrompt = freeformPrompt == null ? "" : freeformPrompt.trim();
        }
    }

    private static List<Clarification> normalizeClarifications(List<String> questions,
            List<Clarification> clarifications) {
        List<Clarification> normalized = new ArrayList<>();
        if (clarifications != null) {
            for (Clarification clarification : clarifications) {
                if (clarification != null && !clarification.question().isBlank()) {
                    normalized.add(clarification);
                }
            }
        }
        if (normalized.isEmpty() && questions != null) {
            for (String question : questions) {
                if (question != null && !question.isBlank()) {
                    normalized.add(new Clarification(question, List.of(), ""));
                }
            }
        }
        return List.copyOf(normalized);
    }
}
