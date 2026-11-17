package com.legaldocinsight.document_service.service.impl;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.sax.BodyContentHandler;
import org.apache.tika.exception.EncryptedDocumentException;
import org.apache.tika.exception.WriteLimitReachedException;

import java.io.InputStream;
import java.io.IOException;
import org.xml.sax.ContentHandler;

@Slf4j
public class TikaExtractionService {

    private final AutoDetectParser parser = new AutoDetectParser();

    public ExtractionResult extract(InputStream inputStream, String fileName) {
        BodyContentHandler handler = new BodyContentHandler(1_000_000);
        Metadata metadata = new Metadata();
        var embedded = new RejectEmbeddedContent();
        var context = new ParseContext();
        context.set(EmbeddedDocumentExtractor.class, embedded);

        try {
            parser.parse(inputStream, handler, metadata, context);

            if (embedded.encountered) {
                return ExtractionResult.builder().success(false).errorCode("UNSAFE_ARCHIVE").build();
            }

            String text = handler.toString().trim();
            int charCount = text.length();
            Integer pageCount = parsePageCount(metadata);

            if (text.isBlank()) {
                return ExtractionResult.builder().success(false).errorCode("EMPTY_TEXT").build();
            }
            log.info("Tika extraction complete: chars={} pages={}", charCount, pageCount);

            return ExtractionResult.builder()
                .text(text)
                .characterCount(charCount)
                .pageCount(pageCount)
                .success(true)
                .build();

        } catch (Exception e) {
            log.warn("Tika extraction failed");
            return ExtractionResult.builder()
                .success(false)
                .errorCode(failureCode(e))
                .build();
        }
    }

    private static final class RejectEmbeddedContent implements EmbeddedDocumentExtractor {
        private boolean encountered;

        @Override
        public boolean shouldParseEmbedded(Metadata metadata) {
            encountered = true;
            return false;
        }

        @Override
        public void parseEmbedded(InputStream stream, ContentHandler handler, Metadata metadata,
                                  boolean outputHtml) throws IOException {
            throw new IOException("Embedded content is not supported");
        }
    }

    private static String failureCode(Exception exception) {
        if (WriteLimitReachedException.isWriteLimitReached(exception)) return "OUTPUT_LIMIT_EXCEEDED";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof EncryptedDocumentException) return "ENCRYPTED_DOCUMENT";
        }
        return "EXTRACTION_FAILED";
    }

    private Integer parsePageCount(Metadata metadata) {
        String[] keys = {"xmpTPg:NPages", "meta:page-count", "Page-Count"};
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null) {
                try {
                    int count = Integer.parseInt(value.trim());
                    if (count > 0) return count;
                }
                catch (NumberFormatException ignored) {}
            }
        }
        return null;
    }

    @Builder
    @Getter
    public static class ExtractionResult {
        private final String text;
        private final int characterCount;
        private final Integer pageCount;
        private final boolean success;
        private final String errorMessage;
        private final String errorCode;
    }
}
