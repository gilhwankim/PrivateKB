ALTER TABLE ingestion_job
    DROP CONSTRAINT ingestion_job_status_check;

ALTER TABLE ingestion_job
    ADD CONSTRAINT ingestion_job_status_check CHECK (status IN (
        'RECEIVED', 'STORED', 'PARSING', 'OCR_PENDING', 'OCR_RUNNING', 'PARSED', 'FAILED'
    ));
