package io.privatekb.ingestion.internal.parsing;

import io.privatekb.ingestion.internal.domain.ExtractedText;
import io.privatekb.ingestion.internal.domain.IngestionErrorCode;
import io.privatekb.ingestion.internal.config.IngestionProperties;
import io.privatekb.ingestion.internal.domain.OcrRequiredException;
import io.privatekb.ingestion.internal.domain.TextExtractionException;
import io.privatekb.ingestion.internal.application.port.TextExtractionService;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.EmptyParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.pdf.PDFParserConfig;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.xml.sax.SAXException;

@Component
class TikaTextExtractionService implements TextExtractionService {

    private static final String PARSER_NAME = "Apache Tika 3.3.2";

    private final ExecutorService parserExecutor;
    private final IngestionProperties properties;

    TikaTextExtractionService(
            @Qualifier("tikaParserExecutor") ExecutorService parserExecutor,
            IngestionProperties properties
    ) {
        this.parserExecutor = parserExecutor;
        this.properties = properties;
    }

    @Override
    public ExtractedText extract(InputStream source, String filename, String detectedMediaType) {
        Future<ExtractedText> future = parserExecutor.submit(
                () -> parse(source, filename, detectedMediaType)
        );
        try {
            return future.get(properties.parsingTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw new TextExtractionException(IngestionErrorCode.PARSE_TIMEOUT, exception);
        } catch (InterruptedException exception) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new TextExtractionException(IngestionErrorCode.PARSE_FAILED, exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof TextExtractionException extractionException) {
                throw extractionException;
            }
            if (cause instanceof OcrRequiredException ocrRequiredException) {
                throw ocrRequiredException;
            }
            throw new TextExtractionException(IngestionErrorCode.PARSE_FAILED, cause);
        }
    }

    private ExtractedText parse(InputStream source, String filename, String detectedMediaType) {
        AutoDetectParser parser = new AutoDetectParser();
        BodyContentHandler handler = new BodyContentHandler(properties.maxExtractedCharacters());
        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, filename);
        metadata.set(HttpHeaders.CONTENT_TYPE, detectedMediaType);

        ParseContext context = new ParseContext();
        PDFParserConfig pdf = new PDFParserConfig();
        pdf.setOcrStrategy(PDFParserConfig.OCR_STRATEGY.NO_OCR);
        pdf.setExtractInlineImages(false);
        context.set(PDFParserConfig.class, pdf);

        OfficeParserConfig office = new OfficeParserConfig();
        office.setExtractMacros(false);
        office.setIncludeDeletedContent(false);
        office.setUseSAXDocxExtractor(true);
        office.setUseSAXPptxExtractor(true);
        context.set(OfficeParserConfig.class, office);

        // 문서 안에 포함된 별도 파일은 현재 문서의 본문으로 재귀 파싱하지 않는다.
        context.set(Parser.class, EmptyParser.INSTANCE);

        try {
            parser.parse(source, handler, metadata, context);
            String text = handler.toString().strip();
            if (text.isBlank() && isPdf(detectedMediaType)) {
                throw new OcrRequiredException();
            }
            if (text.isBlank()) {
                throw new TextExtractionException(IngestionErrorCode.PARSE_FAILED, null);
            }
            return new ExtractedText(
                    text,
                    detectedMediaType,
                    pageCount(metadata),
                    PARSER_NAME
            );
        } catch (SAXException exception) {
            IngestionErrorCode code = handler.toString().length() >= properties.maxExtractedCharacters()
                    ? IngestionErrorCode.PARSE_LIMIT_EXCEEDED
                    : IngestionErrorCode.PARSE_FAILED;
            throw new TextExtractionException(code, exception);
        } catch (IOException exception) {
            throw new TextExtractionException(IngestionErrorCode.PARSE_IO, exception);
        } catch (OcrRequiredException exception) {
            throw exception;
        } catch (TextExtractionException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new TextExtractionException(IngestionErrorCode.PARSE_FAILED, exception);
        }
    }

    private boolean isPdf(String mediaType) {
        return "application/pdf".equalsIgnoreCase(mediaType);
    }

    private Integer pageCount(Metadata metadata) {
        for (String key : new String[]{"xmpTPg:NPages", "Page-Count", "meta:page-count"}) {
            String value = metadata.get(key);
            if (value != null) {
                try {
                    int parsed = Integer.parseInt(value);
                    if (parsed > 0) {
                        return parsed;
                    }
                } catch (NumberFormatException ignored) {
                    // Ignore malformed parser metadata and keep the page count unknown.
                }
            }
        }
        return null;
    }
}
