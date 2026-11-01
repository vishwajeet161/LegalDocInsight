package com.legaldocinsight.document_service.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalStorageServiceTest {
    @TempDir Path directory;

    @Test
    void failedCopyDoesNotLeaveAPartialFile() throws Exception {
        var storage = new LocalStorageService();
        ReflectionTestUtils.setField(storage, "uploadDir", directory.toString());
        var file = mock(MultipartFile.class);
        when(file.getOriginalFilename()).thenReturn("contract.pdf");
        when(file.getInputStream()).thenReturn(new InputStream() {
            private boolean first = true;
            @Override public int read() throws IOException {
                if (first) { first = false; return 'a'; }
                throw new IOException("synthetic read failure");
            }
        });

        assertThrows(IOException.class, () -> storage.store(file, "document-1"));
        assertFalse(Files.exists(directory.resolve("document-1.pdf")));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.count());
        }
    }
}
