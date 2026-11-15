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
