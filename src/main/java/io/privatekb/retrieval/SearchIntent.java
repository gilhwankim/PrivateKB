package io.privatekb.retrieval;

import java.util.Locale;

enum SearchIntent {
    DOCUMENT_SEARCH,
    FOLDER_LOOKUP,
    FOLDER_SCOPED_DOCUMENT_SEARCH;

    static SearchIntent fromModelValue(String value) {
        if (value == null || value.isBlank()) {
            return DOCUMENT_SEARCH;
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            return DOCUMENT_SEARCH;
        }
    }
}
