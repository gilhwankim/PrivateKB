CREATE INDEX idx_document_chunk_search_scope
    ON document_chunk(workspace_id, embedding_model, embedding_digest);
