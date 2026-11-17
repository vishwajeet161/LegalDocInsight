package com.legaldocinsight.document_service.service.impl;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary;
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode;
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification;
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
    void pdfWithEmbeddedFileDoesNotSucceedWithoutCheckingEmbeddedContent() throws Exception {
        var result = extractor.extract(new ByteArrayInputStream(pdfWithEmbeddedFile()), "synthetic.pdf");
        assertFalse(result.isSuccess());
        assertEquals("UNSAFE_ARCHIVE", result.getErrorCode());
    }

    static byte[] pdfWithEmbeddedFile() throws Exception {
        var output = new ByteArrayOutputStream();
        try (var pdf = new PDDocument()) {
            var page = new PDPage();
            pdf.addPage(page);
            try (var content = new PDPageContentStream(pdf, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(40, 700);
                content.showText("Synthetic agreement");
                content.endText();
            }
            var file = new PDComplexFileSpecification();
            file.setFile("attachment.txt");
            file.setEmbeddedFile(new PDEmbeddedFile(pdf, new ByteArrayInputStream(
                "synthetic embedded content".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
            var tree = new PDEmbeddedFilesNameTreeNode();
            tree.setNames(java.util.Map.of("attachment.txt", file));
            var names = new PDDocumentNameDictionary(pdf.getDocumentCatalog());
            names.setEmbeddedFiles(tree);
            pdf.getDocumentCatalog().setNames(names);
            pdf.save(output);
        }
        return output.toByteArray();
    }

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
