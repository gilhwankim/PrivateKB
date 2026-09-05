package io.privatekb.ingestion.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.privatekb.PrivateKbApplication;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore.IndexedChunk;
import io.privatekb.ingestion.internal.application.port.IndexingJobStore.IndexingWorkItem;
import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;

import java.util.List;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@Tag("runtime-db")
@EnabledIfEnvironmentVariable(named = "PRIVATEKB_TEST_DB_URL", matches = ".+")
@SpringBootTest(
        classes = PrivateKbApplication.class,
        properties = {
            "spring.datasource.url=${PRIVATEKB_TEST_DB_URL}",
            "spring.datasource.username=${PRIVATEKB_TEST_DB_USER}",
            "spring.datasource.password=${PRIVATEKB_TEST_DB_PASSWORD}",
            "privatekb.local-ai.startup-check-enabled=false"
        }
)
class ProgressiveIndexingRuntimeTest {

    private static final UUID WORKSPACE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001"
    );

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JdbcIndexingJobStore jobs;

    @Test
    @Transactional
    void stagesIdempotentBatchAndActivatesOnlyCompleteDocument() {
        IndexingWorkItem work = seedWork("INDEXING");
        UUID documentId = work.documentId();
        UUID versionId = work.documentVersionId();
        UUID jobId = work.indexingJobId();
        EmbeddingModelInfo model = new EmbeddingModelInfo("test-embedding", "digest", 1024);

        assertThat(jobs.prepare(
                work, model, "a".repeat(64), "boundary-v1", 1200, 180
        ).nextChunkIndex()).isZero();

        List<IndexedChunk> batch = List.of(
                chunk(jobId, 0, 0, 4, "첫 청크"),
                chunk(jobId, 1, 4, 8, "둘째 청크")
        );
        jobs.appendBatch(work, model, batch, 0);
        jobs.appendBatch(work, model, batch, 0);

        assertThat(count("document_chunk_staging", jobId, true)).isEqualTo(2);
        assertThat(count("document_chunk", versionId, false)).isZero();

        jobs.complete(work, model, 2);

        assertThat(count("document_chunk_staging", jobId, true)).isZero();
        assertThat(count("document_chunk", versionId, false)).isEqualTo(2);
        assertThat(jdbc.sql("""
                        SELECT status FROM privatekb.indexing_job
                         WHERE indexing_job_id = :jobId
                        """)
                .param("jobId", jobId)
                .query(String.class)
                .single()).isEqualTo("INDEXED");
    }

    @Test
    @Transactional
    void cleansOnlyExpiredFailedCheckpointAndRecoversInterruptedJob() {
        IndexingWorkItem work = seedWork("INDEXING");
        EmbeddingModelInfo model = new EmbeddingModelInfo("test-embedding", "digest", 1024);
        jobs.prepare(work, model, "a".repeat(64), "boundary-v1", 1200, 180);
        jobs.appendBatch(
                work,
                model,
                List.of(chunk(work.indexingJobId(), 0, 0, 4, "첫 청크")),
                0
        );
        jdbc.sql("""
                        UPDATE privatekb.indexing_job
                           SET status = 'FAILED', checkpoint_updated_at = now() - interval '4 days'
                         WHERE indexing_job_id = :jobId
                        """)
                .param("jobId", work.indexingJobId())
                .update();

        assertThat(jobs.purgeExpiredStaging(Duration.ofHours(72))).isEqualTo(1);
        assertThat(count("document_chunk_staging", work.indexingJobId(), true)).isZero();
        assertThat(jdbc.sql("""
                        SELECT checkpoint_chunk_index || ':' || error_code
                          FROM privatekb.indexing_job
                         WHERE indexing_job_id = :jobId
                        """)
                .param("jobId", work.indexingJobId())
                .query(String.class)
                .single()).isEqualTo("0:CHECKPOINT_EXPIRED");

        IndexingWorkItem interrupted = seedWork("INDEXING");
        assertThat(jobs.recoverInterruptedJobs()).isEqualTo(1);
        assertThat(jdbc.sql("""
                        SELECT status FROM privatekb.indexing_job
                         WHERE indexing_job_id = :jobId
                        """)
                .param("jobId", interrupted.indexingJobId())
                .query(String.class)
                .single()).isEqualTo("PAUSED");
    }

    @Test
    @Transactional
    void recordsCompletedSourceCopyCleanupWithoutRemovingDocumentMetadata() {
        IndexingWorkItem work = seedWork("INDEXED");

        assertThat(jobs.findCompletedSourceCopiesPendingCleanup(500))
                .extracting(item -> item.documentVersionId())
                .contains(work.documentVersionId());

        jobs.markSourceCopyDeleted(work.documentVersionId());

        assertThat(jobs.findCompletedSourceCopiesPendingCleanup(500))
                .extracting(item -> item.documentVersionId())
                .doesNotContain(work.documentVersionId());
        assertThat(jdbc.sql("""
                        SELECT source_copy_deleted_at IS NOT NULL
                          FROM privatekb.document_version
                         WHERE document_version_id = :versionId
                        """)
                .param("versionId", work.documentVersionId())
                .query(Boolean.class)
                .single()).isTrue();
    }

    private IndexingWorkItem seedWork(String status) {
        UUID documentId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        jdbc.sql("INSERT INTO privatekb.document(document_id, workspace_id) VALUES (:id, :workspace)")
                .param("id", documentId)
                .param("workspace", WORKSPACE_ID)
                .update();
        jdbc.sql("""
                        INSERT INTO privatekb.document_version (
                            document_version_id, document_id, workspace_id, version_number,
                            original_filename, declared_media_type, detected_media_type,
                            byte_size, sha256, source_storage_key
                        ) VALUES (
                            :versionId, :documentId, :workspaceId, 1,
                            '검증.txt', 'text/plain', 'text/plain',
                            10, :sha256, :storageKey
                        )
                        """)
                .param("versionId", versionId)
                .param("documentId", documentId)
                .param("workspaceId", WORKSPACE_ID)
                .param("sha256", versionId.toString().replace("-", "").repeat(2))
                .param("storageKey", WORKSPACE_ID + "/" + versionId + "/source.bin")
                .update();
        jdbc.sql("""
                        INSERT INTO privatekb.indexing_job (
                            indexing_job_id, document_version_id, workspace_id, status
                        ) VALUES (:jobId, :versionId, :workspaceId, :status)
                        """)
                .param("jobId", jobId)
                .param("versionId", versionId)
                .param("workspaceId", WORKSPACE_ID)
                .param("status", status)
                .update();
        return new IndexingWorkItem(
                jobId, WORKSPACE_ID, documentId, versionId,
                "검증.txt", WORKSPACE_ID + "/" + versionId + "/source.bin",
                "unused/extracted.txt"
        );
    }

    private IndexedChunk chunk(UUID jobId, int index, int start, int end, String content) {
        float[] embedding = new float[1024];
        embedding[index] = 1.0f;
        return new IndexedChunk(
                UUID.nameUUIDFromBytes((jobId + ":" + index).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                index,
                start,
                end,
                content,
                embedding
        );
    }

    private int count(String table, UUID id, boolean staging) {
        String column = staging ? "indexing_job_id" : "document_version_id";
        return jdbc.sql("SELECT count(*) FROM privatekb." + table + " WHERE " + column + " = :id")
                .param("id", id)
                .query(Integer.class)
                .single();
    }
}
