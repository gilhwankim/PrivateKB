CREATE TABLE workspace (
    workspace_id uuid PRIMARY KEY,
    name varchar(120) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO workspace (workspace_id, name)
VALUES ('00000000-0000-0000-0000-000000000001', '기본 로컬 작업공간');

CREATE TABLE document (
    document_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(workspace_id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (document_id, workspace_id)
);

CREATE TABLE document_version (
    document_version_id uuid PRIMARY KEY,
    document_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    version_number integer NOT NULL CHECK (version_number > 0),
    original_filename varchar(255) NOT NULL,
    declared_media_type varchar(120) NOT NULL,
    detected_media_type varchar(120) NOT NULL,
    byte_size bigint NOT NULL CHECK (byte_size > 0),
    sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    source_storage_key varchar(500) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_document_version_document
        FOREIGN KEY (document_id, workspace_id)
        REFERENCES document(document_id, workspace_id),
    UNIQUE (document_id, version_number),
    UNIQUE (workspace_id, sha256),
    UNIQUE (document_version_id, workspace_id)
);

CREATE INDEX idx_document_version_workspace_created
    ON document_version(workspace_id, created_at DESC);

CREATE TABLE ingestion_job (
    ingestion_job_id uuid PRIMARY KEY,
    document_version_id uuid NOT NULL,
    workspace_id uuid NOT NULL,
    status varchar(32) NOT NULL CHECK (status IN (
        'RECEIVED', 'STORED', 'PARSING', 'PARSED', 'FAILED'
    )),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    error_code varchar(64),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_ingestion_job_version
        FOREIGN KEY (document_version_id, workspace_id)
        REFERENCES document_version(document_version_id, workspace_id),
    UNIQUE (document_version_id)
);

CREATE INDEX idx_ingestion_job_workspace_status
    ON ingestion_job(workspace_id, status, updated_at DESC);

CREATE TABLE ingestion_job_transition (
    transition_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ingestion_job_id uuid NOT NULL REFERENCES ingestion_job(ingestion_job_id) ON DELETE CASCADE,
    from_status varchar(32),
    to_status varchar(32) NOT NULL,
    reason_code varchar(64),
    occurred_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX idx_ingestion_transition_job_time
    ON ingestion_job_transition(ingestion_job_id, occurred_at);

CREATE TABLE extracted_content (
    document_version_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL,
    storage_key varchar(500) NOT NULL UNIQUE,
    media_type varchar(120) NOT NULL,
    character_count integer NOT NULL CHECK (character_count >= 0),
    page_count integer CHECK (page_count IS NULL OR page_count > 0),
    parser_name varchar(80) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_extracted_content_version
        FOREIGN KEY (document_version_id, workspace_id)
        REFERENCES document_version(document_version_id, workspace_id)
);
