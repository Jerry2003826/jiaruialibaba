package com.example.agentdemo.migration;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowLockedSpecMigrationTest {

    @Test
    void v16AddsNullableLockedSpecToDefinitionsAndRevisions() throws Exception {
        String migration = Files.readString(Path.of(
                "src/main/resources/db/migration/V16__workflow_locked_spec.sql"));

        assertThat(migration)
                .contains("alter table workflow_definitions add column locked_spec_json text")
                .contains("alter table workflow_definition_revisions add column locked_spec_json text")
                .doesNotContain("not null");
    }
}
