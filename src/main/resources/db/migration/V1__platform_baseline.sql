CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE schema_metadata (
    metadata_key text PRIMARY KEY,
    metadata_value text NOT NULL,
    updated_at timestamptz NOT NULL DEFAULT now()
);

INSERT INTO schema_metadata (metadata_key, metadata_value)
VALUES
    ('embedding.model', 'qwen3-embedding:0.6b'),
    ('embedding.dimensions', '1024')
ON CONFLICT (metadata_key) DO NOTHING;
