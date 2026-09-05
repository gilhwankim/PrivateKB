package io.privatekb.platform;

import static org.assertj.core.api.Assertions.assertThat;

import io.privatekb.PrivateKbApplication;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Tag("container")
@Testcontainers
@SpringBootTest(
        classes = PrivateKbApplication.class,
        properties = "privatekb.local-ai.startup-check-enabled=false"
)
class PostgresPgvectorSmokeTest {

    private static final DockerImageName PGVECTOR = DockerImageName
            .parse("pgvector/pgvector:0.8.6-pg18-trixie")
            .asCompatibleSubstituteFor("postgres");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer postgres = new PostgreSQLContainer(PGVECTOR)
            .withDatabaseName("privatekb")
            .withUsername("privatekb")
            .withPassword("integration-test-only");

    @Autowired
    JdbcClient jdbc;

    @Test
    @Transactional
    void flywayCreatesPgvectorBaselineWithExpectedDimensions() {
        String extensionVersion = jdbc.sql("SELECT extversion FROM pg_extension WHERE extname = 'vector'")
                .query(String.class)
                .single();
        String dimensions = jdbc.sql("SELECT metadata_value FROM privatekb.schema_metadata WHERE metadata_key = 'embedding.dimensions'")
                .query(String.class)
                .single();
        String chatProfile = jdbc.sql("SELECT setting_value FROM privatekb.application_setting WHERE setting_key = 'chat.profile'")
                .query(String.class)
                .single();
        Integer indexingTables = jdbc.sql("""
                        SELECT count(*)
                          FROM information_schema.tables
                         WHERE table_schema = 'privatekb'
                           AND table_name IN (
                               'indexing_job', 'indexing_job_transition',
                               'document_chunk', 'document_chunk_staging'
                           )
                        """)
                .query(Integer.class)
                .single();
        String vectorColumnType = jdbc.sql("""
                        SELECT format_type(a.atttypid, a.atttypmod)
                          FROM pg_attribute a
                          JOIN pg_class c ON c.oid = a.attrelid
                          JOIN pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'privatekb'
                           AND c.relname = 'document_chunk'
                           AND a.attname = 'embedding'
                        """)
                .query(String.class)
                .single();
        Integer searchIndexes = jdbc.sql("""
                        SELECT count(*)
                          FROM pg_indexes
                         WHERE schemaname = 'privatekb'
                           AND indexname IN (
                               'idx_document_chunk_keyword',
                               'idx_document_chunk_embedding',
                               'idx_document_chunk_search_scope'
                           )
                        """)
                .query(Integer.class)
                .single();
        Integer folderSearchIndexes = jdbc.sql("""
                        SELECT count(*)
                          FROM pg_indexes
                         WHERE schemaname = 'privatekb'
                           AND indexname IN (
                               'idx_document_source_folder_path_search_trgm',
                               'idx_source_folder_root_path_search_trgm'
                           )
                        """)
                .query(Integer.class)
                .single();

        assertThat(extensionVersion).isEqualTo("0.8.6");
        assertThat(dimensions).isEqualTo("1024");
        assertThat(chatProfile).isEqualTo("DISABLED");
        assertThat(indexingTables).isEqualTo(4);
        assertThat(vectorColumnType).isEqualTo("vector(1024)");
        assertThat(searchIndexes).isEqualTo(3);
        assertThat(folderSearchIndexes).isEqualTo(2);

        jdbc.sql("SET LOCAL enable_seqscan = off").update();
        jdbc.sql("SET LOCAL enable_sort = off").update();
        String vectorPlan = String.join("\n", jdbc.sql("""
                        EXPLAIN (COSTS OFF)
                        SELECT chunk_id
                          FROM privatekb.document_chunk
                         ORDER BY embedding <=> CAST(:embedding AS vector)
                         LIMIT 20
                        """)
                .param("embedding", zeroVector())
                .query(String.class)
                .list());
        assertThat(vectorPlan).contains("idx_document_chunk_embedding");

        jdbc.sql("SET LOCAL enable_sort = on").update();
        String keywordPlan = String.join("\n", jdbc.sql("""
                        EXPLAIN (COSTS OFF)
                        SELECT chunk_id
                          FROM privatekb.document_chunk
                         WHERE content_search @@ plainto_tsquery('simple', '장애 대응')
                        """)
                .query(String.class)
                .list());
        assertThat(keywordPlan).contains("idx_document_chunk_keyword");
    }

    private String zeroVector() {
        return "[" + "0,".repeat(1023) + "0]";
    }
}
