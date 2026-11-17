package com.legaldocinsight.document_service.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void storageLogsDoNotRevealNamesOrPathsEvenAtDebugLevel() throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(LocalStorageService.class);
        var previousLevel = logger.getLevel();
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        Path privateDirectory = directory.resolve("synthetic-private-directory");
        String filename = "synthetic-private-name.pdf";
        try {
            var storage = new LocalStorageService();
            ReflectionTestUtils.setField(storage, "uploadDir", privateDirectory.toString());
            storage.init();
            String path = storage.store(new MockMultipartFile("file", filename, "application/pdf", new byte[] {1}), "document-1");
            storage.delete(path);
            assertTrue(captured.list.stream().noneMatch(event -> event.getFormattedMessage().contains(filename)));
            assertTrue(captured.list.stream().noneMatch(event -> event.getFormattedMessage().contains(privateDirectory.toString())));
        } finally {
            logger.detachAppender(captured);
            logger.setLevel(previousLevel);
            captured.stop();
        }
    }
}
