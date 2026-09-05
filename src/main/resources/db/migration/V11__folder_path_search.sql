ALTER TABLE document_source_location
    ADD COLUMN folder_path_search text
    GENERATED ALWAYS AS (
        regexp_replace(
            lower(replace(source_path, chr(92), '/')),
            '/[^/]+$',
            ''
        )
    ) STORED;

ALTER TABLE source_folder
    ADD COLUMN root_path_search text
    GENERATED ALWAYS AS (lower(replace(root_path, chr(92), '/'))) STORED;

CREATE INDEX idx_document_source_folder_path_search_trgm
    ON document_source_location USING gin (folder_path_search gin_trgm_ops);

CREATE INDEX idx_source_folder_root_path_search_trgm
    ON source_folder USING gin (root_path_search gin_trgm_ops);
