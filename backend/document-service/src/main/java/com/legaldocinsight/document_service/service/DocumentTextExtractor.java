package com.legaldocinsight.document_service.service;

import org.apache.tika.Tika;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class DocumentTextExtractor {

    private final Tika tika = new Tika();

    public String extractText(File file) throws Exception {
        return tika.parseToString(file);
    }
}