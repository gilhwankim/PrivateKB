ALTER TABLE document_version
    ADD COLUMN source_copy_deleted_at timestamptz;

COMMENT ON COLUMN document_version.source_copy_deleted_at IS
    '파싱과 임베딩 완료 후 PrivateKB 관리 원본 사본을 삭제한 시각';
