ALTER TABLE indexing_job
    DROP CONSTRAINT indexing_job_status_check;

ALTER TABLE indexing_job
    ADD CONSTRAINT indexing_job_status_check CHECK (status IN (
        'PENDING', 'MODEL_WAITING', 'REINDEX_REQUIRED', 'INDEXING',
        'PAUSED', 'INDEXED', 'FAILED'
    ));

ALTER TABLE indexing_job
    ADD COLUMN checkpoint_chunk_index integer NOT NULL DEFAULT 0
        CHECK (checkpoint_chunk_index >= 0),
    ADD COLUMN checkpoint_updated_at timestamptz,
    ADD COLUMN extracted_sha256 char(64)
        CHECK (extracted_sha256 IS NULL OR extracted_sha256 ~ '^[0-9a-f]{64}$'),
    ADD COLUMN chunking_version varchar(32),
    ADD COLUMN chunk_size integer CHECK (chunk_size IS NULL OR chunk_size > 0),
    ADD COLUMN chunk_overlap integer CHECK (chunk_overlap IS NULL OR chunk_overlap >= 0);

CREATE TABLE document_chunk_staging (
    chunk_id uuid PRIMARY KEY,
    indexing_job_id uuid NOT NULL
        REFERENCES indexing_job(indexing_job_id) ON DELETE CASCADE,
    workspace_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    start_offset integer NOT NULL CHECK (start_offset >= 0),
    end_offset integer NOT NULL CHECK (end_offset > start_offset),
    content text NOT NULL CHECK (length(content) > 0),
    embedding vector(1024) NOT NULL,
    embedding_model varchar(120) NOT NULL,
    embedding_digest varchar(128),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_staging_chunk_version
        FOREIGN KEY (document_version_id, workspace_id)
        REFERENCES document_version(document_version_id, workspace_id),
    CONSTRAINT fk_staging_chunk_document
        FOREIGN KEY (document_id, workspace_id)
        REFERENCES document(document_id, workspace_id),
    UNIQUE (indexing_job_id, chunk_index)
);

CREATE INDEX idx_staging_chunk_job_order
    ON document_chunk_staging(indexing_job_id, chunk_index);

UPDATE indexing_job
   SET status = 'PAUSED',
       error_code = 'PROCESS_INTERRUPTED',
       updated_at = now()
 WHERE status = 'INDEXING';
