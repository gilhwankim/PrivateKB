package io.privatekb.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.privatekb.platform.LocalChatClient;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

class AnswerQueryPlannerTest {

    private LocalChatClient chat;
    private AnswerQueryPlanner planner;

    @BeforeEach
    void setUp() {
        chat = mock(LocalChatClient.class);
        planner = new AnswerQueryPlanner(
                chat,
                new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-28T01:00:00Z"), ZoneOffset.UTC)
        );
    }

    @Test
    void usesAiPlanForRelativeDateFileSearch() {
        when(chat.complete(any(), eq(256))).thenReturn("""
                {"searchQuery":"2026-08-27 회의록","filenameHint":"2026-08-27","extensionHint":".txt"}
                """);

        AnswerQueryPlan plan = planner.plan("어제 진행했던 회의록 파일 찾아서 요약해줘");

        assertThat(plan).isEqualTo(new AnswerQueryPlan(
                "2026-08-27 회의록",
                "2026-08-27",
                ""
        ));
    }

    @Test
    void appliesDocumentExtensionOnlyWhenUserExplicitlyRequestsIt() {
        when(chat.complete(any(), eq(256))).thenReturn("""
                {"searchQuery":"2026-08-27 회의록","filenameHint":"2026-08-27","extensionHint":".hwp"}
                """);

        assertThat(planner.plan("어제 회의록을 PDF로 찾아줘").extensionHint()).isEqualTo(".pdf");
        assertThat(planner.plan("어제 회의록을 찾아줘").extensionHint()).isEmpty();
    }

    @Test
    void fallsBackSafelyWhenModelDoesNotReturnJson() {
        when(chat.complete(any(), eq(256))).thenReturn("검색 계획을 만들 수 없습니다.");

        AnswerQueryPlan plan = planner.plan("고용보험 파일을 찾아줘");

        assertThat(plan.searchQuery()).isEqualTo("고용보험 파일을 찾아줘");
        assertThat(plan.filenameHint()).isEqualTo("고용보험");
        assertThat(plan.extensionHint()).isEmpty();
    }

    @Test
    void separatesFolderScopeTopicAndDocumentType() {
        when(chat.complete(any(), eq(256))).thenReturn("""
                {
                  "intent":"FOLDER_SCOPED_DOCUMENT_SEARCH",
                  "searchQuery":"비용 정산",
                  "folderHint":"OO회사",
                  "documentType":"회의록",
                  "filenameHint":"회의록",
                  "extensionHint":""
                }
                """);

        AnswerQueryPlan plan = planner.plan("OO회사 관련 비용 정산 회의록 어디 있어?");

        assertThat(plan.intent()).isEqualTo(SearchIntent.FOLDER_SCOPED_DOCUMENT_SEARCH);
        assertThat(plan.folderHint()).isEqualTo("OO회사");
        assertThat(plan.semanticQuery(false)).isEqualTo("비용 정산 회의록");
    }

    @Test
    void acceptsFolderLookupWithoutSemanticQuery() {
        when(chat.complete(any(), eq(256))).thenReturn("""
                {
                  "intent":"FOLDER_LOOKUP",
                  "searchQuery":"",
                  "folderHint":"OO회사",
                  "documentType":"",
                  "filenameHint":"",
                  "extensionHint":""
                }
                """);

        AnswerQueryPlan plan = planner.plan("OO회사 폴더 어디 있어?");

        assertThat(plan.requestsFolderLookup()).isTrue();
        assertThat(plan.folderHint()).isEqualTo("OO회사");
        assertThat(plan.searchQuery()).isEmpty();
    }

    @Test
    void convertsYesterdayUploadRequestToKoreanDayBoundaryWithoutAiPlanning() {
        AnswerQueryPlan plan = planner.plan("어제 업로드한 파일 찾아줘");

        assertThat(plan).isEqualTo(new AnswerQueryPlan(
                "",
                "",
                "",
                Instant.parse("2026-08-26T15:00:00Z"),
                Instant.parse("2026-08-27T15:00:00Z"),
                true
        ));
        assertThat(plan.metadataOnly()).isTrue();
        verifyNoInteractions(chat);
    }

    @Test
    void keepsTopicAndExtensionForRecentUploadRequest() {
        AnswerQueryPlan plan = planner.plan("최근에 업로드한 고용보험 PDF 파일 찾아줘");

        assertThat(plan).isEqualTo(new AnswerQueryPlan(
                "고용보험",
                "고용보험",
                ".pdf",
                null,
                null,
                true
        ));
        assertThat(plan.metadataOnly()).isFalse();
        verifyNoInteractions(chat);
    }

    @Test
    void sortsMetadataOnlyRecentUploadRequestByNewestFirst() {
        AnswerQueryPlan plan = planner.plan("최근에 업로드한 파일 찾아줘");

        assertThat(plan).isEqualTo(new AnswerQueryPlan(
                "",
                "",
                "",
                null,
                null,
                true
        ));
        assertThat(plan.metadataOnly()).isTrue();
        verifyNoInteractions(chat);
    }
}
