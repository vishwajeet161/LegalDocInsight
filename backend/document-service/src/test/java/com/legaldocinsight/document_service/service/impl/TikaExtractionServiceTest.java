package com.legaldocinsight.document_service.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TikaExtractionServiceTest {
    private final TikaExtractionService extractor = new TikaExtractionService();

    @Test
    void emptyPdfIsNotReportedAsSuccessfulExtraction() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.save(output);
        }
        assertFalse(extractor.extract(new ByteArrayInputStream(output.toByteArray()), "synthetic.pdf").isSuccess());
    }

    @Test
    void encryptedPdfReturnsASafeDistinctFailure() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            pdf.addPage(new PDPage());
            pdf.protect(new StandardProtectionPolicy("synthetic-owner", "synthetic-user", new AccessPermission()));
            pdf.save(output);
        }
        var result = extractor.extract(new ByteArrayInputStream(output.toByteArray()), "synthetic.pdf");
        assertFalse(result.isSuccess());
        assertEquals("ENCRYPTED_DOCUMENT", result.getErrorCode());
    }

    @Test
    void docxHasTextAndDoesNotInventAZeroPageCount() throws Exception {
        var result = extractor.extract(new ByteArrayInputStream(docx("Synthetic agreement")), "synthetic.docx");
        assertTrue(result.isSuccess());
        assertEquals("Synthetic agreement", result.getText());
        assertEquals(19, result.getCharacterCount());
        assertNull(result.getPageCount());
    }

    @Test
    void rejectsExcessiveExtractedText() throws Exception {
        byte[] random = new byte[760_000];
        new java.util.Random(42).nextBytes(random);
        String text = java.util.Base64.getEncoder().encodeToString(random);
        var result = extractor.extract(new ByteArrayInputStream(docx(text)), "synthetic.docx");
        assertFalse(result.isSuccess());
        assertEquals("OUTPUT_LIMIT_EXCEEDED", result.getErrorCode());
    }

    private static byte[] docx(String text) throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }
}
