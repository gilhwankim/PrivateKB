CREATE EXTENSION IF NOT EXISTS pg_trgm;

ALTER TABLE document_version
    ADD COLUMN filename_search text
    GENERATED ALWAYS AS (lower(original_filename)) STORED;

CREATE INDEX idx_document_version_filename_search_trgm
    ON document_version USING gin (filename_search gin_trgm_ops);
