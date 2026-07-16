package com.example.agentdemo.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowArtifactMigrationTest {

    @Test
    void v18CreatesOwnerAndAppScopedArtifactMetadata() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V18__workflow_artifacts.sql"));

        assertThat(migration)
                .contains("CREATE TABLE workflow_artifacts")
                .contains("owner_id VARCHAR(128) NOT NULL")
                .contains("app_id VARCHAR(64)")
                .contains("storage_key VARCHAR(255) NOT NULL UNIQUE")
                .contains("expires_at TIMESTAMP WITH TIME ZONE NOT NULL")
                .contains("idx_workflow_artifacts_owner_run")
                .contains("idx_workflow_artifacts_expires");
    }
}
