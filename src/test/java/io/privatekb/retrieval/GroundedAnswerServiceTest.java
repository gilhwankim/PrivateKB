package io.privatekb.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.privatekb.knowledge.DocumentCatalog;
import io.privatekb.platform.LocalChatClient;
import io.privatekb.platform.LocalChatClient.ChatModelInfo;
import io.privatekb.platform.LocalChatException;
import io.privatekb.platform.LocalEmbeddingClient;
import io.privatekb.platform.LocalEmbeddingClient.EmbeddingModelInfo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GroundedAnswerServiceTest {

    private static final UUID WORKSPACE_ID = UUID.randomUUID();
    private static final EmbeddingModelInfo EMBEDDING_MODEL =
            new EmbeddingModelInfo("qwen3-embedding:0.6b", "embedding-digest", 1024);

    private DocumentCatalog documents;
    private LocalEmbeddingClient embeddings;
    private LocalChatClient chat;
    private SemanticSearchRepository repository;
    private AnswerQueryPlanner queryPlanner;
    private GroundedAnswerService service;

    @BeforeEach
    void setUp() {
        documents = mock(DocumentCatalog.class);
        embeddings = mock(LocalEmbeddingClient.class);
        chat = mock(LocalChatClient.class);
        repository = mock(SemanticSearchRepository.class);
        queryPlanner = mock(AnswerQueryPlanner.class);
        service = new GroundedAnswerService(
                documents,
                embeddings,
                chat,
                repository,
                queryPlanner,
                new SearchProperties(2, 500, 10, 8, 20, 100, 0.40),
                new AnswerProperties(2, 1000, 6, 0.50, 12_000, Duration.ofMinutes(3))
        );
        when(documents.workspaceExists(WORKSPACE_ID)).thenReturn(true);
        when(embeddings.verifyModel()).thenReturn(EMBEDDING_MODEL);
        when(embeddings.embed(any())).thenReturn(List.of(new float[1024]));
        when(chat.verifyModel()).thenReturn(new ChatModelInfo("qwen3.5:4b", "chat-digest"));
        when(queryPlanner.plan(anyString())).thenAnswer(invocation -> {
            String question = invocation.getArgument(0);
            return new AnswerQueryPlan(question, "", "");
        });
    }

    @Test
    void buildsGroundedPromptAndCitationsFromQualifiedChunks() {
        SearchResultView source = source(0.78, "경보 임계치를 조정하기로 결정했습니다.");
        when(repository.search(
                eq(WORKSPACE_ID),
                eq("장애 후속 조치는?"),
                anyString(),
                anyString(),
                anyString(),
                isNull(),
                isNull(),
                eq(false),
                any(float[].class),
                eq(EMBEDDING_MODEL),
                eq(48),
                eq(0.50),
                eq(1),
                eq(6)
        )).thenReturn(List.of(source));

        GroundedAnswerPlan plan = service.prepare(WORKSPACE_ID, " 장애 후속 조치는? ");

        assertThat(plan.refused()).isFalse();
        assertThat(plan.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.sourceNumber()).isEqualTo(1);
            assertThat(citation.originalFilename()).isEqualTo("장애대응회의록.txt");
            assertThat(citation.score()).isEqualTo(0.78);
        });
        assertThat(plan.messages()).hasSize(2);
        assertThat(plan.messages().get(0).content())
                .contains("문서명", "업로드 시각", "먼저 명확하게 나열");
        assertThat(plan.messages().get(1).content())
                .contains("질문:\n장애 후속 조치는?", "[자료 1]", source.content());
    }

    @Test
    void keepsOnlyHighestRankedChunkForEachDocumentInPromptAndCitations() {
        UUID repeatedDocument = UUID.randomUUID();
        SearchResultView highest = source(
                repeatedDocument,
                0,
                0.82,
                "동일 문서의 최고 점수 구간"
        );
        SearchResultView duplicate = source(
                repeatedDocument,
                1,
                0.76,
                "동일 문서의 두 번째 구간"
        );
        SearchResultView another = source(
                UUID.randomUUID(),
                "비용정책.txt",
                0,
                0.70,
                "다른 문서의 근거 구간"
        );
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(Integer.class), anyDouble(), eq(1), any(Integer.class)))
                .thenReturn(List.of(highest, duplicate, another));

        GroundedAnswerPlan plan = service.prepare(WORKSPACE_ID, "비용 결재 절차는?");

        assertThat(plan.citations())
                .extracting(GroundedCitation::documentId)
                .containsExactly(highest.documentId(), another.documentId())
                .doesNotHaveDuplicates();
        assertThat(plan.messages().get(1).content())
                .contains(highest.content(), another.content())
                .doesNotContain(duplicate.content());
    }

    @Test
    void refusesWithoutCallingChatGenerationWhenGroundingScoreIsTooLow() {
        when(repository.search(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(Integer.class), anyDouble(), any(Integer.class), any(Integer.class)))
                .thenReturn(List.of(source(0.49, "관련성이 낮은 내용")));

        GroundedAnswerPlan plan = service.prepare(WORKSPACE_ID, "장애 후속 조치는?");

        assertThat(plan.refused()).isTrue();
        assertThat(plan.refusalReason()).contains("근거");
        verify(chat, never()).stream(any(), any(), any());
    }

    @Test
    void reportsUnavailableWhenExactChatModelIsMissing() {
        when(chat.verifyModel()).thenThrow(new LocalChatException(
                LocalChatException.Reason.MODEL_NOT_AVAILABLE
        ));

        assertThatThrownBy(() -> service.prepare(WORKSPACE_ID, "장애 후속 조치는?"))
                .isInstanceOfSatisfying(AnswerUnavailableException.class, exception ->
                        assertThat(exception.code()).isEqualTo("CHAT_MODEL_NOT_AVAILABLE")
                );
        verify(repository, never()).search(any(), any(), any(), any(), any(), any(), any(), anyBoolean(), any(), any(), any(Integer.class), anyDouble(), any(Integer.class), any(Integer.class));
        verify(repository, never()).searchByUploadTime(any(), any(), any(), any(), any(Integer.class));
    }

    @Test
    void retrievesUploadTimeOnlyRequestWithoutCreatingQueryEmbedding() {
        Instant from = Instant.parse("2026-08-26T15:00:00Z");
        Instant to = Instant.parse("2026-08-27T15:00:00Z");
        when(queryPlanner.plan("어제 업로드한 파일 찾아줘")).thenReturn(new AnswerQueryPlan(
                "",
                "",
                "",
                from,
                to,
                true
        ));
        when(repository.searchByUploadTime(WORKSPACE_ID, "", from, to, 6))
                .thenReturn(List.of(source(1.0, "어제 업로드한 문서 내용")));

        GroundedAnswerPlan plan = service.prepare(WORKSPACE_ID, "어제 업로드한 파일 찾아줘");

        assertThat(plan.refused()).isFalse();
        assertThat(plan.messages().get(1).content())
                .contains("업로드 시각: 2026-08-27 09:00 (Asia/Seoul)");
        verify(embeddings, never()).verifyModel();
        verify(embeddings, never()).embed(any());
    }

    @Test
    void returnsConfirmedFolderLocationsWithoutCreatingQueryEmbedding() {
        when(queryPlanner.plan("OO회사 폴더 어디 있어?")).thenReturn(new AnswerQueryPlan(
                SearchIntent.FOLDER_LOOKUP,
                "",
                "OO회사",
                "",
                "",
                "",
                null,
                null,
                false
        ));
        SearchResultView folderSource = source(
                UUID.randomUUID(),
                "비용회의록.txt",
                0,
                1.0,
                "비용 회의 내용",
                "C:\\업무\\고객사\\OO회사"
        );
        when(repository.searchByFolder(WORKSPACE_ID, "OO회사", 48))
                .thenReturn(List.of(folderSource));

        GroundedAnswerPlan plan = service.prepare(WORKSPACE_ID, "OO회사 폴더 어디 있어?");

        assertThat(plan.refused()).isFalse();
        assertThat(plan.citations()).singleElement().satisfies(citation ->
                assertThat(citation.sourceFolderPath()).isEqualTo("C:\\업무\\고객사\\OO회사")
        );
        assertThat(plan.messages().get(1).content())
                .contains("원본 폴더: C:\\업무\\고객사\\OO회사");
        verify(embeddings, never()).verifyModel();
        verify(embeddings, never()).embed(any());
    }

    @Test
    void restrictsSemanticSearchToConfirmedFolderScope() {
        when(queryPlanner.plan("OO회사 비용 회의록 찾아줘")).thenReturn(new AnswerQueryPlan(
                SearchIntent.FOLDER_SCOPED_DOCUMENT_SEARCH,
                "비용",
                "OO회사",
                "회의록",
                "",
                "",
                null,
                null,
                false
        ));
        when(repository.searchByFolder(WORKSPACE_ID, "OO회사", 48))
                .thenReturn(List.of(source(1.0, "폴더 확인용 문서")));
        when(repository.search(
                eq(WORKSPACE_ID), eq("비용 회의록"), anyString(), anyString(), eq("OO회사"),
                isNull(), isNull(), eq(false), any(float[].class), eq(EMBEDDING_MODEL),
                eq(48), eq(0.50), eq(1), eq(6)
        )).thenReturn(List.of(source(0.83, "비용 회의 결정 내용")));

        GroundedAnswerPlan plan = service.prepare(WORKSPACE_ID, "OO회사 비용 회의록 찾아줘");

        assertThat(plan.refused()).isFalse();
        verify(embeddings).embed(any());
    }

    private SearchResultView source(double score, String content) {
        return source(UUID.randomUUID(), 0, score, content);
    }

    private SearchResultView source(
            UUID documentId,
            int chunkIndex,
            double score,
            String content
    ) {
        return source(documentId, "장애대응회의록.txt", chunkIndex, score, content);
    }

    private SearchResultView source(
            UUID documentId,
            String originalFilename,
            int chunkIndex,
            double score,
            String content
    ) {
        return source(documentId, originalFilename, chunkIndex, score, content, null);
    }

    private SearchResultView source(
            UUID documentId,
            String originalFilename,
            int chunkIndex,
            double score,
            String content,
            String sourceFolderPath
    ) {
        return new SearchResultView(
                UUID.randomUUID(),
                documentId,
                UUID.randomUUID(),
                originalFilename,
                1,
                Instant.parse("2026-08-27T00:00:00Z"),
                chunkIndex,
                0,
                content.length(),
                content,
                sourceFolderPath,
                score
        );
    }
}
