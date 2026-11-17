package com.legaldocinsight.document_service.service.impl;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ClamAvScannerTest {
    @Test
    void returnsCleanOnlyWhenDaemonReportsClean() throws Exception {
        assertEquals(ClamAvScanner.Result.CLEAN, scanWithReply("stream: OK\0"));
    }

    @Test
    void reportsDetectionWithoutReturningTheSignature() throws Exception {
        assertEquals(ClamAvScanner.Result.INFECTED, scanWithReply("stream: Synthetic-Signature FOUND\0"));
    }

    @Test
    void identifiesScanLimitAlertsSeparatelyFromMalware() throws Exception {
        assertEquals("SCAN_LIMIT_EXCEEDED",
            scanWithReply("stream: Heuristics.Limits.Exceeded.MaxScanSize FOUND\0").name());
    }

    @Test
    void daemonErrorsFailClosed() throws Exception {
        assertEquals(ClamAvScanner.Result.UNAVAILABLE, scanWithReply("stream: size limit ERROR\0"));
    }

    @Test
    void unavailableDaemonFailsClosed() throws Exception {
        int port;
        try (var server = new ServerSocket(0)) { port = server.getLocalPort(); }
        var scanner = new ClamAvScanner("127.0.0.1", port, Duration.ofSeconds(1));
        assertEquals(ClamAvScanner.Result.UNAVAILABLE, scanner.scan(input()));
    }

    @Test
    void unresponsiveDaemonReachesDeadline() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        var release = new CountDownLatch(1);
        try (var server = new ServerSocket(0)) {
            var request = executor.submit(() -> {
                try (var socket = server.accept()) {
                    release.await(3, TimeUnit.SECONDS);
                }
                return null;
            });
            try {
                var scanner = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofMillis(200));
                assertEquals(ClamAvScanner.Result.TIMEOUT, scanner.scan(input()));
            } finally {
                release.countDown();
            }
            request.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void staleLoadedSignaturesFailClosedBeforeScanning() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            var request = executor.submit(() -> {
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    String command = readRecord(input);
                    if ("zVERSION".equals(command)) {
                        socket.getOutputStream().write(
                            "ClamAV 1.5.4/1/Mon Jan 01 00:00:00 2001\0".getBytes(StandardCharsets.US_ASCII));
                    } else if ("zINSTREAM".equals(command)) {
                        int size = input.readInt();
                        input.readNBytes(size);
                        input.readInt();
                        socket.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                    }
                }
                return null;
            });
            var scanner = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));
            assertEquals(ClamAvScanner.Result.UNAVAILABLE, scanner.scan(input()));
            request.get(3, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void checksLoadedSignatureFreshnessAgainBeforeEachScan() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            server.setSoTimeout(2000);
            var requests = executor.submit(() -> {
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    assertEquals("zVERSION", readRecord(input));
                    String date = ZonedDateTime.now(ZoneOffset.UTC).format(
                        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US));
                    socket.getOutputStream().write(("ClamAV 1.5.4/1/" + date + "\0").getBytes(StandardCharsets.US_ASCII));
                }
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    assertEquals("zINSTREAM", readRecord(input));
                    int size = input.readInt();
                    input.readNBytes(size);
                    assertEquals(0, input.readInt());
                    socket.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                }
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    assertEquals("zVERSION", readRecord(input));
                    socket.getOutputStream().write(
                        "ClamAV 1.5.4/1/Mon Jan 01 00:00:00 2001\0".getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            var scanner = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));
            assertEquals(ClamAvScanner.Result.CLEAN, scanner.scan(input()));
            assertEquals(ClamAvScanner.Result.UNAVAILABLE, scanner.scan(input()));
            requests.get(3, TimeUnit.SECONDS);
            server.setSoTimeout(200);
            org.junit.jupiter.api.Assertions.assertThrows(java.net.SocketTimeoutException.class, server::accept);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void malformedSignatureVersionFailsClosed() throws Exception {
        assertEquals(ClamAvScanner.Result.UNAVAILABLE, scanWithVersion("ClamAV unknown\0"));
    }

    @Test
    void futureSignatureTimestampFailsClosed() throws Exception {
        String date = ZonedDateTime.now(ZoneOffset.UTC).plusDays(2).format(
            DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US));
        assertEquals(ClamAvScanner.Result.UNAVAILABLE, scanWithVersion("ClamAV 1.5.4/1/" + date + "\0"));
    }

    private ClamAvScanner.Result scanWithVersion(String version) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            var request = executor.submit(() -> {
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    assertEquals("zVERSION", readRecord(input));
                    socket.getOutputStream().write(version.getBytes(StandardCharsets.US_ASCII));
                }
                return null;
            });
            var result = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2)).scan(input());
            request.get(3, TimeUnit.SECONDS);
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private ClamAvScanner.Result scanWithReply(String reply) throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (var server = new ServerSocket(0)) {
            var request = executor.submit(() -> {
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    assertEquals("zVERSION", readRecord(input));
                    String date = ZonedDateTime.now(ZoneOffset.UTC).format(
                        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US));
                    socket.getOutputStream().write(("ClamAV 1.5.4/1/" + date + "\0").getBytes(StandardCharsets.US_ASCII));
                }
                try (var socket = server.accept(); var input = new DataInputStream(socket.getInputStream())) {
                    assertEquals("zINSTREAM", readRecord(input));
                    int size = input.readInt();
                    assertEquals("synthetic input", new String(input.readNBytes(size), StandardCharsets.UTF_8));
                    assertEquals(0, input.readInt());
                    socket.getOutputStream().write(reply.getBytes(StandardCharsets.UTF_8));
                }
                return null;
            });
            var scanner = new ClamAvScanner("127.0.0.1", server.getLocalPort(), Duration.ofSeconds(2));
            var result = scanner.scan(input());
            request.get(3, TimeUnit.SECONDS);
            return result;
        } finally {
            executor.shutdownNow();
        }
    }

    private static String readRecord(DataInputStream input) throws Exception {
        var value = new StringBuilder();
        int next;
        while ((next = input.read()) > 0) value.append((char) next);
        return value.toString();
    }

    private static ByteArrayInputStream input() {
        return new ByteArrayInputStream("synthetic input".getBytes(StandardCharsets.UTF_8));
    }
}
