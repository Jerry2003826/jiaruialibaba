package com.example.agentdemo.workflow.report;

import java.nio.file.Path;

public interface WorkflowArtifactStorage {

    void store(String storageKey, byte[] content);

    Path resolve(String storageKey);

    void delete(String storageKey);
}
