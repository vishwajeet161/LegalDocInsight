package com.legaldocinsight.document_service.service.impl;

import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;

import java.io.InputStream;

@Slf4j
@Service
public class TikaExtractionService {

    private final AutoDetectParser parser = new AutoDetectParser();

    public ExtractionResult extract(InputStream inputStream, String fileName) {
        BodyContentHandler handler = new BodyContentHandler(1_000_000);
        Metadata metadata = new Metadata();

        try {
            parser.parse(inputStream, handler, metadata);

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
                .errorCode("EXTRACTION_FAILED")
                .build();
        }
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
