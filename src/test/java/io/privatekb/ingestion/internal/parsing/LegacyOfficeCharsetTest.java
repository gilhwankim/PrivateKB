package io.privatekb.ingestion.internal.parsing;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;

import org.apache.poi.hwpf.util.DoubleByteUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LegacyOfficeCharsetTest {

    @ParameterizedTest
    @CsvSource({
            "EUC-KR,한글 문서 검증",
            "MS949,한글 문서 검증",
            "Big5,中文測試",
            "MS932,日本語",
            "GBK,中文测试"
    })
    void decodesLegacyOfficeTextWithoutReplacement(String name, String text) {
        assertThat(Charset.isSupported(name)).as("배포용 Java 문자셋 %s", name).isTrue();
        Charset charset = Charset.forName(name);
        assertThat(new String(text.getBytes(charset), charset)).isEqualTo(text);
    }

    @Test
    void initializesBinaryWordDoubleByteDecoder() {
        // POI의 .doc 디코더는 본문 언어와 관계없이 초기화 때 Big5를 조회한다.
        assertThat(DoubleByteUtil.BIG5.name()).isEqualTo("Big5");
    }
}
