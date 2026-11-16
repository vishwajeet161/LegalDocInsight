package com.legaldocinsight.document_service.service.impl;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.PushbackInputStream;
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
        var budget = new ArchiveBudget();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            return inspectArchive(zip, budget, 0, true);
        } catch (ArchiveLimitExceeded ex) {
            return "UNSAFE_ARCHIVE";
        }
    }

    private static String inspectArchive(ZipInputStream zip, ArchiveBudget budget, int depth, boolean docx)
            throws IOException {
        boolean contentTypes = false;
        boolean documentXml = false;
        var names = new java.util.HashSet<String>();
        byte[] buffer = new byte[8192];
        java.util.zip.ZipEntry entry;
        while ((entry = zip.getNextEntry()) != null) {
            if (++budget.entries > 1000) throw new ArchiveLimitExceeded();
            if (!names.add(entry.getName())) return "MALFORMED_DOCUMENT";
            contentTypes |= entry.getName().equals("[Content_Types].xml");
            documentXml |= entry.getName().equals("word/document.xml");
            var contents = new PushbackInputStream(new CountedEntryStream(zip, budget), 4);
            byte[] signature = contents.readNBytes(4);
            contents.unread(signature);
            if (signature.length == 4 && signature[0] == 'P' && signature[1] == 'K'
                    && signature[2] == 3 && signature[3] == 4) {
                if (depth >= 4) throw new ArchiveLimitExceeded();
                try (var nested = new ZipInputStream(new NonClosingStream(contents))) {
                    String failure = inspectArchive(nested, budget, depth + 1, false);
                    if (failure != null) return failure;
                }
            }
            while (contents.read(buffer) != -1) { /* Count and discard this entry. */ }
            zip.closeEntry();
        }
        return !docx || (contentTypes && documentXml) ? null : "MALFORMED_DOCUMENT";
    }

    private static final class ArchiveBudget {
        private long expanded;
        private int entries;
    }

    private static final class ArchiveLimitExceeded extends IOException { }

    private static class NonClosingStream extends FilterInputStream {
        NonClosingStream(InputStream input) { super(input); }
        @Override public void close() { }
    }

    private static final class CountedEntryStream extends NonClosingStream {
        private final ArchiveBudget budget;
        CountedEntryStream(InputStream input, ArchiveBudget budget) {
            super(input);
            this.budget = budget;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = in.read(bytes, offset, length);
            if (count > 0 && (budget.expanded += count) > 128L * 1024 * 1024) {
                throw new ArchiveLimitExceeded();
            }
            return count;
        }
        @Override public int read() throws IOException {
            byte[] value = new byte[1];
            return read(value, 0, 1) == -1 ? -1 : value[0] & 0xff;
        }
    }
}
