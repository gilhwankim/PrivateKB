package io.privatekb.retrieval;

import java.time.Instant;

record AnswerQueryPlan(
        SearchIntent intent,
        String searchQuery,
        String folderHint,
        String documentType,
        String filenameHint,
        String extensionHint,
        Instant uploadedFrom,
        Instant uploadedToExclusive,
        boolean sortByUploadedAtDescending
) {

    AnswerQueryPlan(String searchQuery, String filenameHint, String extensionHint) {
        this(
                SearchIntent.DOCUMENT_SEARCH,
                searchQuery,
                "",
                "",
                filenameHint,
                extensionHint,
                null,
                null,
                false
        );
    }

    AnswerQueryPlan(
            String searchQuery,
            String filenameHint,
            String extensionHint,
            Instant uploadedFrom,
            Instant uploadedToExclusive,
            boolean sortByUploadedAtDescending
    ) {
        this(
                SearchIntent.DOCUMENT_SEARCH,
                searchQuery,
                "",
                "",
                filenameHint,
                extensionHint,
                uploadedFrom,
                uploadedToExclusive,
                sortByUploadedAtDescending
        );
    }

    AnswerQueryPlan {
        intent = intent == null ? SearchIntent.DOCUMENT_SEARCH : intent;
        searchQuery = searchQuery == null ? "" : searchQuery.strip();
        folderHint = folderHint == null ? "" : folderHint.strip();
        documentType = documentType == null ? "" : documentType.strip();
        filenameHint = filenameHint == null ? "" : filenameHint.strip();
        extensionHint = extensionHint == null ? "" : extensionHint.strip();
    }

    boolean metadataOnly() {
        return searchQuery.isBlank()
                && (uploadedFrom != null || uploadedToExclusive != null
                || sortByUploadedAtDescending);
    }

    boolean requestsFolderLookup() {
        return intent == SearchIntent.FOLDER_LOOKUP;
    }

    String semanticQuery(boolean includeFolderHint) {
        StringBuilder query = new StringBuilder(searchQuery);
        appendDistinct(query, documentType);
        if (includeFolderHint) {
            appendDistinct(query, folderHint);
        }
        return query.toString().strip();
    }

    private void appendDistinct(StringBuilder target, String value) {
        if (value.isBlank() || target.toString().contains(value)) {
            return;
        }
        if (!target.isEmpty()) {
            target.append(' ');
        }
        target.append(value);
    }
}
