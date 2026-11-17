package com.legaldocinsight.document_service.service.impl;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserChildProcessTest {
    @Test
    void childRejectsPdfWithEmbeddedFile() throws Exception {
        assertArchiveRejected(TikaExtractionServiceTest.pdfWithEmbeddedFile());
    }

    @Test
    void childExtractsSyntheticDocxAndReportsUnknownPageCount() throws Exception {
        byte[] fixture;
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Synthetic agreement");
            document.write(bytes);
            fixture = bytes.toByteArray();
        }
        Process child = startChild();
        try (var input = new DataOutputStream(child.getOutputStream())) {
            input.writeInt(fixture.length);
            input.write(fixture);
        }
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        try (var response = new DataInputStream(child.getInputStream())) {
            assertEquals(0x4c445031, response.readInt());
            assertTrue(response.readBoolean());
            assertEquals(-1, response.readInt());
            assertEquals(19, response.readInt());
            int length = response.readInt();
            assertEquals("Synthetic agreement", new String(response.readNBytes(length), StandardCharsets.UTF_8));
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void childRejectsAnOversizedInputWithoutReadingIt() throws Exception {
        Process child = startChild();
        try (var input = new DataOutputStream(child.getOutputStream())) {
            input.writeInt(10 * 1024 * 1024 + 1);
        }
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        try (var response = new DataInputStream(child.getInputStream())) {
            assertEquals(0x4c445031, response.readInt());
            assertEquals(false, response.readBoolean());
            assertEquals("INPUT_LIMIT_EXCEEDED", response.readUTF());
        } finally {
            child.destroyForcibly();
        }
    }

    @Test
    void childRejectsAnArchiveWithTooManyEntries() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            for (int index = 0; index < 1001; index++) {
                zip.putNextEntry(new ZipEntry("entry-" + index));
                zip.closeEntry();
            }
        }
        assertArchiveRejected(bytes.toByteArray());
    }

    @Test
    void childRejectsAnArchiveThatExpandsPastTheLimit() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("synthetic"));
            byte[] block = new byte[64 * 1024];
            for (int index = 0; index < 2049; index++) zip.write(block);
            zip.closeEntry();
        }
        assertArchiveRejected(bytes.toByteArray());
    }

    @Test
    void childRejectsNestedZipThatExpandsPastTheLimit() throws Exception {
        var nested = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(nested)) {
            zip.putNextEntry(new ZipEntry("large.txt"));
            byte[] block = new byte[64 * 1024];
            for (int index = 0; index < 2049; index++) zip.write(block);
            zip.closeEntry();
        }
        assertArchiveRejected(docxWithEmbeddedZip(nested.toByteArray()));
    }

    @Test
    void childRejectsNestedZipHiddenBehindPrefixBytes() throws Exception {
        var nested = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(nested)) {
            zip.putNextEntry(new ZipEntry("synthetic.txt"));
            zip.write("synthetic content".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        for (int prefixLength : new int[] {16, 8194}) {
            var prefixed = new ByteArrayOutputStream();
            prefixed.write(new byte[prefixLength]);
            prefixed.write(nested.toByteArray());
            assertArchiveRejected(docxWithEmbeddedZip(prefixed.toByteArray()));
        }
    }

    @Test
    void childRejectsASecondArchiveAppendedToANestedZip() throws Exception {
        var first = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(first)) {
            zip.putNextEntry(new ZipEntry("first.txt"));
            zip.write("synthetic first archive".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        var second = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(second)) {
            zip.putNextEntry(new ZipEntry("second.txt"));
            zip.write("synthetic second archive".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        first.write(second.toByteArray());
        assertArchiveRejected(docxWithEmbeddedZip(first.toByteArray()));
    }

    @Test
    void childRejectsASecondArchiveAppendedToTheDocx() throws Exception {
        var fixture = new ByteArrayOutputStream();
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Synthetic agreement");
            document.write(fixture);
        }
        var appended = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(appended)) {
            zip.putNextEntry(new ZipEntry("synthetic.txt"));
            zip.write("synthetic second archive".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        fixture.write(appended.toByteArray());
        assertArchiveRejected(fixture.toByteArray());
    }

    @Test
    void childRejectsNestedZipWithTooManyEntries() throws Exception {
        var nested = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(nested)) {
            for (int index = 0; index < 1001; index++) {
                zip.putNextEntry(new ZipEntry("entry-" + index));
                zip.closeEntry();
            }
        }
        assertArchiveRejected(docxWithEmbeddedZip(nested.toByteArray()));
    }

    @Test
    void childRejectsNestedZipBeyondTheDepthLimit() throws Exception {
        byte[] nested = "synthetic".getBytes(StandardCharsets.UTF_8);
        for (int depth = 0; depth < 5; depth++) {
            var bytes = new ByteArrayOutputStream();
            try (var zip = new ZipOutputStream(bytes)) {
                zip.putNextEntry(new ZipEntry("nested"));
                zip.write(nested);
                zip.closeEntry();
            }
            nested = bytes.toByteArray();
        }
        assertArchiveRejected(docxWithEmbeddedZip(nested));
    }

    @Test
    void childCountsEntriesAcrossNestedArchives() throws Exception {
        var branch = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(branch)) {
            for (int index = 0; index < 500; index++) {
                zip.putNextEntry(new ZipEntry("entry-" + index));
                zip.closeEntry();
            }
        }
        var nested = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(nested)) {
            for (String name : new String[] {"first.zip", "second.zip"}) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(branch.toByteArray());
                zip.closeEntry();
            }
        }
        assertArchiveRejected(docxWithEmbeddedZip(nested.toByteArray()));
    }

    @Test
    void childAcceptsDocxContainingSmallNestedZip() throws Exception {
        var nested = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(nested)) {
            zip.putNextEntry(new ZipEntry("note.txt"));
            zip.write("synthetic attachment".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        byte[] fixture = docxWithEmbeddedZip(nested.toByteArray());
        Process child = startChild();
        try {
            try (var input = new DataOutputStream(child.getOutputStream())) {
                input.writeInt(fixture.length);
                input.write(fixture);
            }
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            try (var response = new DataInputStream(child.getInputStream())) {
                assertEquals(0x4c445031, response.readInt());
                assertTrue(response.readBoolean());
                assertEquals(-1, response.readInt());
                int characters = response.readInt();
                int length = response.readInt();
                String text = new String(response.readNBytes(length), StandardCharsets.UTF_8);
                assertEquals(text.length(), characters);
                assertTrue(text.contains("Synthetic agreement"));
            }
        } finally {
            child.destroyForcibly();
        }
    }

    private static byte[] docxWithEmbeddedZip(byte[] nested) throws Exception {
        var result = new ByteArrayOutputStream();
        try (var document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("Synthetic agreement");
            var part = document.getPackage().createPart(
                org.apache.poi.openxml4j.opc.PackagingURIHelper.createPartName("/word/embeddings/synthetic.zip"),
                "application/zip");
            try (var output = part.getOutputStream()) {
                output.write(nested);
            }
            document.write(result);
        }
        return result.toByteArray();
    }

    @Test
    void childRejectsAnOrdinaryZipThatIsNotADocx() throws Exception {
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("synthetic.txt"));
            zip.write("synthetic text".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        Process child = startChild();
        try (var input = new DataOutputStream(child.getOutputStream())) {
            input.writeInt(bytes.size());
            input.write(bytes.toByteArray());
        }
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        try (var response = new DataInputStream(child.getInputStream())) {
            assertEquals(0x4c445031, response.readInt());
            assertEquals(false, response.readBoolean());
            assertEquals("MALFORMED_DOCUMENT", response.readUTF());
        } finally {
            child.destroyForcibly();
        }
    }

    private static void assertArchiveRejected(byte[] fixture) throws Exception {
        Process child = startChild();
        try (var input = new DataOutputStream(child.getOutputStream())) {
            input.writeInt(fixture.length);
            input.write(fixture);
        }
        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        assertEquals(0, child.exitValue());
        try (var response = new DataInputStream(child.getInputStream())) {
            assertEquals(0x4c445031, response.readInt());
            assertEquals(false, response.readBoolean());
            assertEquals("UNSAFE_ARCHIVE", response.readUTF());
        } finally {
            child.destroyForcibly();
        }
    }

    private static Process startChild() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        return new ProcessBuilder(java, "-cp", System.getProperty("java.class.path"),
            "com.legaldocinsight.document_service.service.impl.ParserChildMain")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
    }
}
