package com.legaldocinsight.document_service.service.impl;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipInputStream;

/** Private, single-request protocol for a parser subprocess. */
public final class ParserChildMain {
    private static final int MAGIC = 0x4c445031;
    private static final int MAX_INPUT_BYTES = 10 * 1024 * 1024;

    private ParserChildMain() { }

    public static void main(String[] args) throws Exception {
        var output = new DataOutputStream(System.out);
        // Framework and parser diagnostics must never corrupt the binary reply.
        System.setOut(System.err);
        var input = new DataInputStream(System.in);
        int length = input.readInt();
        if (length < 0 || length > MAX_INPUT_BYTES) {
            failure(output, "INPUT_LIMIT_EXCEEDED");
            return;
        }
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            failure(output, "INTERNAL_ERROR");
            return;
        }
        if (bytes.length >= 2 && bytes[0] == 'P' && bytes[1] == 'K') {
            try {
                String archiveFailure = archiveFailure(bytes);
                if (archiveFailure != null) {
                    failure(output, archiveFailure);
                    return;
                }
            } catch (IOException ex) {
                failure(output, "MALFORMED_DOCUMENT");
                return;
            }
        }
        var result = new TikaExtractionService().extract(new ByteArrayInputStream(bytes), "document");
        if (!result.isSuccess()) {
            failure(output, result.getErrorCode() == null ? "EXTRACTION_FAILED" : result.getErrorCode());
            return;
        }
        byte[] text = result.getText().getBytes(StandardCharsets.UTF_8);
        output.writeInt(MAGIC);
        output.writeBoolean(true);
        output.writeInt(result.getPageCount() == null ? -1 : result.getPageCount());
        output.writeInt(result.getCharacterCount());
        output.writeInt(text.length);
        output.write(text);
        output.flush();
    }

    private static void failure(DataOutputStream output, String code) throws Exception {
        output.writeInt(MAGIC);
        output.writeBoolean(false);
        output.writeUTF(code);
        output.flush();
    }

    private static String archiveFailure(byte[] bytes) throws IOException {
        long expanded = 0;
        int entries = 0;
        boolean contentTypes = false;
        boolean documentXml = false;
        var names = new java.util.HashSet<String>();
        byte[] buffer = new byte[8192];
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 1000) return "UNSAFE_ARCHIVE";
                if (!names.add(entry.getName())) return "MALFORMED_DOCUMENT";
                contentTypes |= entry.getName().equals("[Content_Types].xml");
                documentXml |= entry.getName().equals("word/document.xml");
                int count;
                while ((count = zip.read(buffer)) != -1) {
                    expanded += count;
                    if (expanded > 128L * 1024 * 1024) return "UNSAFE_ARCHIVE";
                }
                zip.closeEntry();
            }
        }
        return contentTypes && documentXml ? null : "MALFORMED_DOCUMENT";
    }
}
