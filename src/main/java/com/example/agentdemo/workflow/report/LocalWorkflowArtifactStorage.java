package com.example.agentdemo.workflow.report;

import com.example.agentdemo.common.BusinessException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Component
public class LocalWorkflowArtifactStorage implements WorkflowArtifactStorage {

    private final Path root;

    public LocalWorkflowArtifactStorage(WorkflowArtifactProperties properties) {
        this.root = Path.of(properties.getStorageRoot()).toAbsolutePath().normalize();
    }

    @Override
    public void store(String storageKey, byte[] content) {
        Path target = safePath(storageKey);
        Path temporary = null;
        try {
            Files.createDirectories(target.getParent());
            temporary = Files.createTempFile(target.getParent(), ".artifact-", ".tmp");
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            }
            catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (IOException ex) {
            if (temporary != null) {
                try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            }
            throw new BusinessException("REPORT_EXPORT_FAILED", "Failed to store report artifact", ex);
        }
    }

    @Override
    public Path resolve(String storageKey) {
        Path target = safePath(storageKey);
        if (!Files.isRegularFile(target)) {
            throw new BusinessException("ARTIFACT_NOT_FOUND", "Report artifact content was not found");
        }
        return target;
    }

    @Override
    public void delete(String storageKey) {
        try {
            Files.deleteIfExists(safePath(storageKey));
        }
        catch (IOException ex) {
            throw new BusinessException("REPORT_ARTIFACT_DELETE_FAILED", "Failed to delete report artifact", ex);
        }
    }

    private Path safePath(String storageKey) {
        Path target = root.resolve(storageKey).normalize();
        if (!target.startsWith(root)) {
            throw new BusinessException("ARTIFACT_STORAGE_KEY_INVALID", "Invalid artifact storage key");
        }
        return target;
    }
}
