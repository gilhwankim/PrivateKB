package io.privatekb.retrieval;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RetrievalQueryTest {

    @Test
    void formatsQwenRetrievalInstructionWithoutChangingDocumentEmbeddings() {
        assertThat(RetrievalQuery.format("회의록을 찾아줘"))
                .startsWith("Instruct: ")
                .endsWith("Query: 회의록을 찾아줘");
    }

    @Test
    void extractsFilenameAndDocumentTypeHints() {
        assertThat(RetrievalQuery.filenameHint("최근 회의록을 찾아줘")).isEqualTo("회의");
        assertThat(RetrievalQuery.filenameHint("고용산재보험 자격 이력 문서")).isEqualTo("고용산재보험");
        assertThat(RetrievalQuery.extensionHint("비용 관련 PDF 파일을 찾아줘")).isEqualTo(".pdf");
    }
}
