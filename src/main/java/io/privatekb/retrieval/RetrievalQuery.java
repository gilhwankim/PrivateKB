package io.privatekb.retrieval;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class RetrievalQuery {

    private static final String TASK =
            "Given a user question, retrieve relevant passages from private business documents that answer the question.";
    private static final Set<String> FILENAME_STOP_WORDS = Set.of(
            "검색", "검색해줘", "관련", "내용", "문서", "문서들", "파일", "파일들",
            "알려줘", "보여줘", "찾아", "찾아줘", "있는", "있어", "최근", "대한"
    );
    private static final Map<String, String> EXTENSION_HINTS = Map.ofEntries(
            Map.entry("pdf", ".pdf"),
            Map.entry("워드", ".doc"),
            Map.entry("docx", ".doc"),
            Map.entry("파워포인트", ".ppt"),
            Map.entry("ppt", ".ppt"),
            Map.entry("엑셀", ".xls"),
            Map.entry("xlsx", ".xls"),
            Map.entry("한글", ".hwp"),
            Map.entry("hwp", ".hwp"),
            Map.entry("텍스트", ".txt"),
            Map.entry("txt", ".txt"),
            Map.entry("마크다운", ".md"),
            Map.entry("markdown", ".md")
    );

    private RetrievalQuery() {
    }

    static String format(String query) {
        return "Instruct: " + TASK + "\nQuery: " + query;
    }

    static String filenameHint(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        if (normalized.contains("회의록")) {
            return "회의";
        }
        return Arrays.stream(normalized.split("[^0-9a-z가-힣]+"))
                .map(RetrievalQuery::removeTrailingParticle)
                .filter(token -> token.length() >= 2)
                .filter(token -> !FILENAME_STOP_WORDS.contains(token))
                .filter(token -> EXTENSION_HINTS.keySet().stream().noneMatch(token::contains))
                .max(Comparator.comparingInt(String::length))
                .orElse("");
    }

    static String extensionHint(String query) {
        String normalized = query.toLowerCase(Locale.ROOT);
        return EXTENSION_HINTS.entrySet().stream()
                .filter(entry -> normalized.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse("");
    }

    private static String removeTrailingParticle(String token) {
        return token.replaceFirst("(으로|에서|에게|까지|부터|처럼|보다|은|는|이|가|을|를|의|에|로|와|과)$", "");
    }
}
