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
        BodyContentHandler handler = new BodyContentHandler(-1); // -1 = no char limit
        Metadata metadata = new Metadata();

        try {
            parser.parse(inputStream, handler, metadata);

            String text = handler.toString().trim();
            int charCount = text.length();
            int pageCount = parsePageCount(metadata);

            log.info("Tika extraction complete: file={} chars={} pages={}", fileName, charCount, pageCount);

            return ExtractionResult.builder()
                .text(text)
                .characterCount(charCount)
                .pageCount(pageCount)
                .success(true)
                .build();

        } catch (Exception e) {
            log.error("Tika extraction failed: file={} error={}", fileName, e.getMessage(), e);
            return ExtractionResult.builder()
                .success(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    private int parsePageCount(Metadata metadata) {
        String[] keys = {"xmpTPg:NPages", "meta:page-count", "Page-Count"};
        for (String key : keys) {
            String value = metadata.get(key);
            if (value != null) {
                try { return Integer.parseInt(value.trim()); }
                catch (NumberFormatException ignored) {}
            }
        }
        return 0;
    }

    @Builder
    @Getter
    public static class ExtractionResult {
        private final String text;
        private final int characterCount;
        private final int pageCount;
        private final boolean success;
        private final String errorMessage;
    }
}
