CREATE INDEX idx_document_version_workspace_document_latest
    ON document_version(workspace_id, document_id, version_number DESC)
    INCLUDE (document_version_id, created_at);
