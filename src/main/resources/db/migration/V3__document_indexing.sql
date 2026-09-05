CREATE TABLE indexing_job (
    indexing_job_id uuid PRIMARY KEY,
    document_version_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN (
        'PENDING', 'MODEL_WAITING', 'REINDEX_REQUIRED', 'INDEXING', 'INDEXED', 'FAILED'
    )),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    error_code varchar(64),
    embedding_model varchar(120),
    embedding_digest varchar(128),
    embedding_dimensions integer,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_indexing_job_version
        FOREIGN KEY (document_version_id, workspace_id)
        REFERENCES document_version(document_version_id, workspace_id),
    UNIQUE (document_version_id)
);

CREATE INDEX idx_indexing_job_workspace_status
    ON indexing_job(workspace_id, status, updated_at DESC);

CREATE TABLE indexing_job_transition (
    transition_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    indexing_job_id uuid NOT NULL REFERENCES indexing_job(indexing_job_id) ON DELETE CASCADE,
    from_status varchar(32),
    to_status varchar(32) NOT NULL,
    reason_code varchar(64),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_indexing_transition_job_time
    ON indexing_job_transition(indexing_job_id, occurred_at);

CREATE TABLE document_chunk (
    chunk_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    document_id uuid NOT NULL,
    document_version_id uuid NOT NULL,
    chunk_index integer NOT NULL CHECK (chunk_index >= 0),
    start_offset integer NOT NULL CHECK (start_offset >= 0),
    end_offset integer NOT NULL CHECK (end_offset > start_offset),
    content text NOT NULL CHECK (length(content) > 0),
    content_search tsvector GENERATED ALWAYS AS (
        to_tsvector('simple', content)
    ) STORED,
    embedding vector(1024) NOT NULL,
    embedding_model varchar(120) NOT NULL,
    embedding_digest varchar(128),
    index_version integer NOT NULL DEFAULT 1 CHECK (index_version > 0),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_document_chunk_version
        FOREIGN KEY (document_version_id, workspace_id)
        REFERENCES document_version(document_version_id, workspace_id),
    CONSTRAINT fk_document_chunk_document
        FOREIGN KEY (document_id, workspace_id)
        REFERENCES document(document_id, workspace_id),
    UNIQUE (document_version_id, chunk_index)
);

CREATE INDEX idx_document_chunk_workspace_version
    ON document_chunk(workspace_id, document_version_id, chunk_index);

CREATE INDEX idx_document_chunk_keyword
    ON document_chunk USING gin(content_search);

CREATE INDEX idx_document_chunk_embedding
    ON document_chunk USING hnsw(embedding vector_cosine_ops);

INSERT INTO indexing_job (
    indexing_job_id, document_version_id, workspace_id, status
)
SELECT gen_random_uuid(), j.document_version_id, j.workspace_id, 'PENDING'
  FROM ingestion_job j
  JOIN extracted_content e
    ON e.document_version_id = j.document_version_id
   AND e.workspace_id = j.workspace_id
 WHERE j.status = 'PARSED'
ON CONFLICT (document_version_id) DO NOTHING;

INSERT INTO indexing_job_transition (
    indexing_job_id, from_status, to_status
)
SELECT indexing_job_id, NULL, 'PENDING'
  FROM indexing_job;
