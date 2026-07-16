CREATE TABLE workflow_artifacts (
    artifact_id VARCHAR(64) PRIMARY KEY,
    export_id VARCHAR(64) NOT NULL,
    owner_id VARCHAR(128) NOT NULL,
    run_id VARCHAR(64) NOT NULL,
    app_id VARCHAR(64),
    node_id VARCHAR(128) NOT NULL,
    role VARCHAR(32) NOT NULL,
    format VARCHAR(16) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    mime_type VARCHAR(160) NOT NULL,
    size_bytes BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    storage_key VARCHAR(255) NOT NULL UNIQUE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_workflow_artifacts_owner_run ON workflow_artifacts(owner_id, run_id);
CREATE INDEX idx_workflow_artifacts_expires ON workflow_artifacts(expires_at);
