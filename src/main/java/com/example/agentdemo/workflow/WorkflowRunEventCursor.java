package com.example.agentdemo.workflow;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

public class WorkflowRunEventCursor {

    private Instant lastStartedAt;
    private String lastStepId;
    private final Set<String> sentStepIds = new LinkedHashSet<>();
    private final Set<String> sentTerminalStepIds = new LinkedHashSet<>();
    private boolean runDoneSent;

    public Instant lastStartedAt() {
        return lastStartedAt;
    }

    public String lastStepId() {
        return lastStepId;
    }

    public Set<String> sentStepIds() {
        return Set.copyOf(sentStepIds);
    }

    public boolean runDoneSent() {
        return runDoneSent;
    }

    boolean markStepStarted(String stepId) {
        return sentStepIds.add(stepId);
    }

    boolean stepStartedSent(String stepId) {
        return sentStepIds.contains(stepId);
    }

    int sentStartedCount() {
        return sentStepIds.size();
    }

    boolean markStepTerminal(String stepId) {
        return sentTerminalStepIds.add(stepId);
    }

    Set<String> pendingStepIds() {
        Set<String> pending = new LinkedHashSet<>(sentStepIds);
        pending.removeAll(sentTerminalStepIds);
        return pending;
    }

    void advanceTo(Instant startedAt, String stepId) {
        if (startedAt == null || stepId == null) {
            return;
        }
        if (lastStartedAt == null || startedAt.isAfter(lastStartedAt)
                || (startedAt.equals(lastStartedAt) && stepId.compareTo(lastStepId) > 0)) {
            lastStartedAt = startedAt;
            lastStepId = stepId;
        }
    }

    boolean markRunDoneSent() {
        if (runDoneSent) {
            return false;
        }
        runDoneSent = true;
        return true;
    }

}
