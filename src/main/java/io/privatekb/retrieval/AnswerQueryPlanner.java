package io.privatekb.retrieval;

import io.privatekb.platform.LocalChatClient;
import io.privatekb.platform.LocalChatClient.ChatMessage;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
class AnswerQueryPlanner {

    private static final int MAXIMUM_PLAN_TOKENS = 256;
    private static final int MAXIMUM_SEARCH_QUERY_CHARACTERS = 500;
    private static final int MAXIMUM_FILENAME_HINT_CHARACTERS = 80;
    private static final int MAXIMUM_FOLDER_HINT_CHARACTERS = 120;
    private static final int MAXIMUM_DOCUMENT_TYPE_CHARACTERS = 40;
    private static final ZoneId LOCAL_ZONE = ZoneId.of("Asia/Seoul");
    private static final Pattern EXPLICIT_DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})[./-](\\d{1,2})[./-](\\d{1,2})(?!\\d)"
    );
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            "", ".pdf", ".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx",
            ".hwp", ".md", ".markdown", ".txt"
    );

    private final LocalChatClient chat;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    AnswerQueryPlanner(LocalChatClient chat, ObjectMapper objectMapper, Clock clock) {
        this.chat = chat;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    AnswerQueryPlan plan(String question) {
        LocalDate today = LocalDate.now(clock.withZone(LOCAL_ZONE));
        UploadTimeDirective uploadTime = uploadTimeDirective(question, today);
        if (uploadTime != null) {
            String topic = uploadSearchTopic(question);
            return new AnswerQueryPlan(
                    topic,
                    RetrievalQuery.filenameHint(topic),
                    RetrievalQuery.extensionHint(question),
                    uploadTime.from(),
                    uploadTime.toExclusive(),
                    true
            );
        }
        String systemPrompt = """
                당신은 PrivateKB의 로컬 문서 검색 계획 도우미입니다.
                사용자 요청을 해석해 검색 의도, 폴더 범위, 핵심 주제, 문서 종류, 파일명 단서와 확장자를 결정하세요.
                오늘 날짜는 %s입니다. '어제', '지난주' 같은 상대 날짜는 절대 날짜로 바꾸세요.
                문서 내용이나 파일 목록을 추측하지 마세요.
                반드시 아래 필드만 가진 JSON 객체 하나를 반환하세요. Markdown은 사용하지 마세요.
                {
                  "intent": "DOCUMENT_SEARCH | FOLDER_LOOKUP | FOLDER_SCOPED_DOCUMENT_SEARCH",
                  "searchQuery": "임베딩과 본문 검색에 사용할 간결한 검색문",
                  "folderHint": "폴더명이나 조직명으로 보이는 검색 범위 단서 또는 빈 문자열",
                  "documentType": "회의록, 계약서 같은 문서 종류 또는 빈 문자열",
                  "filenameHint": "파일명에서 찾을 가장 구별력 있는 한 단서 또는 빈 문자열",
                  "extensionHint": "요청한 확장자 또는 빈 문자열"
                }
                폴더 자체의 위치를 요청하면 FOLDER_LOOKUP을 사용하고 searchQuery는 비워 두세요.
                특정 회사나 폴더 안의 문서를 요청하면 FOLDER_SCOPED_DOCUMENT_SEARCH를 사용하세요.
                폴더 범위가 없는 문서 검색이나 내용 질문은 DOCUMENT_SEARCH를 사용하세요.
                회사명이나 조직명으로 보이는 범위 단서는 folderHint에, 찾으려는 업무 내용은 searchQuery에 넣으세요.
                회의록, 계약서, 보고서 같은 종류는 documentType에 넣되 searchQuery와 folderHint에는 반복하지 마세요.
                날짜가 지정된 파일 요청은 searchQuery에 절대 날짜와 문서 종류를 넣고 filenameHint에는 YYYY-MM-DD 날짜를 우선하세요.
                요약, 비교, 설명 같은 작업 지시는 searchQuery에서 제외하고 검색 대상만 남기세요.
                extensionHint는 .pdf, .doc, .docx, .ppt, .pptx, .xls, .xlsx, .hwp, .md, .markdown, .txt 중 하나만 사용할 수 있습니다.
                """.formatted(today);

        String response = chat.complete(List.of(
                new ChatMessage("system", systemPrompt),
                new ChatMessage("user", question)
        ), MAXIMUM_PLAN_TOKENS);
        return parse(response, question);
    }

    private AnswerQueryPlan parse(String response, String question) {
        try {
            int start = response.indexOf('{');
            int end = response.lastIndexOf('}');
            if (start < 0 || end <= start) {
                return fallback(question);
            }
            JsonNode root = objectMapper.readTree(response.substring(start, end + 1));
            SearchIntent intent = SearchIntent.fromModelValue(normalizedText(root, "intent", 48));
            String searchQuery = normalizedText(root, "searchQuery", MAXIMUM_SEARCH_QUERY_CHARACTERS);
            String folderHint = searchableHint(
                    normalizedText(root, "folderHint", MAXIMUM_FOLDER_HINT_CHARACTERS)
            );
            String documentType = searchableHint(
                    normalizedText(root, "documentType", MAXIMUM_DOCUMENT_TYPE_CHARACTERS)
            );
            String filenameHint = normalizedText(root, "filenameHint", MAXIMUM_FILENAME_HINT_CHARACTERS)
                    .transform(this::searchableHint);
            String plannedExtension = normalizedText(root, "extensionHint", 10)
                    .toLowerCase(Locale.ROOT);
            String explicitlyRequestedExtension = RetrievalQuery.extensionHint(question);
            String extensionHint = ALLOWED_EXTENSIONS.contains(plannedExtension)
                    && !explicitlyRequestedExtension.isBlank()
                    ? explicitlyRequestedExtension
                    : "";
            boolean usableFolderPlan = folderHint.length() >= 2
                    && (intent == SearchIntent.FOLDER_LOOKUP
                    || intent == SearchIntent.FOLDER_SCOPED_DOCUMENT_SEARCH);
            if (searchQuery.length() < 2 && !usableFolderPlan) {
                return fallback(question);
            }
            if (filenameHint.isBlank() && !searchQuery.isBlank()) {
                filenameHint = RetrievalQuery.filenameHint(searchQuery);
            }
            return new AnswerQueryPlan(
                    intent,
                    searchQuery,
                    folderHint,
                    documentType,
                    filenameHint,
                    extensionHint,
                    null,
                    null,
                    false
            );
        } catch (RuntimeException exception) {
            return fallback(question);
        }
    }

    private String searchableHint(String value) {
        return value.replaceAll("[^0-9A-Za-z가-힣._ ()-]", " ")
                .replaceAll("\\s+", " ")
                .strip();
    }

    private AnswerQueryPlan fallback(String question) {
        String searchQuery = question.strip();
        return new AnswerQueryPlan(
                searchQuery,
                RetrievalQuery.filenameHint(searchQuery),
                RetrievalQuery.extensionHint(searchQuery)
        );
    }

    private UploadTimeDirective uploadTimeDirective(String question, LocalDate today) {
        String normalized = question.toLowerCase(Locale.ROOT);
        if (!mentionsUploadTime(normalized)) {
            return null;
        }
        if (normalized.contains("어제")) {
            return day(today.minusDays(1));
        }
        if (normalized.contains("오늘")) {
            return day(today);
        }
        Matcher explicitDate = EXPLICIT_DATE.matcher(normalized);
        if (explicitDate.find()) {
            try {
                return day(LocalDate.of(
                        Integer.parseInt(explicitDate.group(1)),
                        Integer.parseInt(explicitDate.group(2)),
                        Integer.parseInt(explicitDate.group(3))
                ));
            } catch (RuntimeException ignored) {
                return null;
            }
        }
        if (normalized.contains("최근") || normalized.contains("방금")) {
            return new UploadTimeDirective(null, null);
        }
        return null;
    }

    private boolean mentionsUploadTime(String question) {
        return question.contains("업로드")
                || question.contains("올린")
                || question.contains("등록한")
                || question.contains("추가한");
    }

    private UploadTimeDirective day(LocalDate date) {
        return new UploadTimeDirective(
                date.atStartOfDay(LOCAL_ZONE).toInstant(),
                date.plusDays(1).atStartOfDay(LOCAL_ZONE).toInstant()
        );
    }

    private String uploadSearchTopic(String question) {
        String withoutDates = EXPLICIT_DATE.matcher(question.toLowerCase(Locale.ROOT))
                .replaceAll(" ");
        return List.of(withoutDates.split("[^0-9a-z가-힣]+"))
                .stream()
                .map(this::removeTrailingParticle)
                .filter(token -> token.length() >= 2)
                .filter(token -> !isUploadMetadataWord(token))
                .collect(Collectors.joining(" "));
    }

    private String removeTrailingParticle(String token) {
        return token.replaceFirst(
                "(으로|에서|에게|까지|부터|처럼|보다|은|는|이|가|을|를|의|에|로|와|과)$",
                ""
        );
    }

    private boolean isUploadMetadataWord(String token) {
        if (token.chars().allMatch(Character::isDigit)) {
            return true;
        }
        return token.startsWith("최근")
                || token.equals("오늘")
                || token.equals("어제")
                || token.startsWith("업로드")
                || token.startsWith("올린")
                || token.startsWith("등록")
                || token.startsWith("추가")
                || token.startsWith("찾")
                || token.startsWith("검색")
                || token.startsWith("보여")
                || token.startsWith("알려")
                || token.startsWith("요약")
                || token.equals("파일")
                || token.equals("파일들")
                || token.equals("문서")
                || token.equals("문서들")
                || Set.of("pdf", "doc", "docx", "ppt", "pptx", "xls", "xlsx",
                        "hwp", "txt", "md", "markdown", "워드", "엑셀", "한글",
                        "파워포인트", "텍스트", "마크다운").contains(token);
    }

    private String normalizedText(JsonNode root, String field, int maximumCharacters) {
        JsonNode value = root.get(field);
        if (value == null || !value.isString()) {
            return "";
        }
        String normalized = value.stringValue().replaceAll("\\s+", " ").strip();
        return normalized.length() <= maximumCharacters
                ? normalized
                : normalized.substring(0, maximumCharacters).strip();
    }

    private record UploadTimeDirective(Instant from, Instant toExclusive) {
    }
}
