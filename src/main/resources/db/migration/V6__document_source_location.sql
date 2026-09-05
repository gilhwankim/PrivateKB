CREATE TABLE source_folder (
    source_folder_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(workspace_id),
    root_path text NOT NULL,
    root_path_hash char(64) NOT NULL CHECK (root_path_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (workspace_id, root_path_hash),
    UNIQUE (source_folder_id, workspace_id)
);

CREATE TABLE document_source_location (
    source_location_id uuid PRIMARY KEY,
    workspace_id uuid NOT NULL REFERENCES workspace(workspace_id),
    document_version_id uuid NOT NULL,
    source_folder_id uuid,
    source_path text NOT NULL,
    source_path_hash char(64) NOT NULL CHECK (source_path_hash ~ '^[0-9a-f]{64}$'),
    relative_path text,
    byte_size bigint NOT NULL CHECK (byte_size > 0),
    last_modified_at timestamptz NOT NULL,
    status varchar(32) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN (
        'AVAILABLE', 'MISSING', 'CHANGED', 'UNAVAILABLE'
    )),
    last_verified_at timestamptz NOT NULL DEFAULT now(),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_document_source_version
        FOREIGN KEY (document_version_id, workspace_id)
        REFERENCES document_version(document_version_id, workspace_id),
    CONSTRAINT fk_document_source_folder
        FOREIGN KEY (source_folder_id, workspace_id)
        REFERENCES source_folder(source_folder_id, workspace_id),
    UNIQUE (workspace_id, source_path_hash)
);

CREATE INDEX idx_document_source_version
    ON document_source_location(workspace_id, document_version_id);

CREATE INDEX idx_document_source_folder
    ON document_source_location(source_folder_id)
    WHERE source_folder_id IS NOT NULL;
