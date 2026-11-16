package com.legaldocinsight.document_service.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** A bounded clamd INSTREAM client. The caller must provide a bounded local file stream. */
public class ClamAvScanner {
    public enum Result { CLEAN, INFECTED, SCAN_LIMIT_EXCEEDED, UNAVAILABLE, TIMEOUT }

    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "scanner-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private static final long MAX_BYTES = 10L * 1024 * 1024;
    private static final Duration MAX_SIGNATURE_AGE = Duration.ofDays(7);
    private static final DateTimeFormatter DATABASE_DATE = DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US);
    private final String host;
    private final int port;
    private final Duration timeout;

    public ClamAvScanner(String host, int port, Duration timeout) {
        if (timeout.isNegative() || timeout.isZero() || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid scanner deadline");
        }
        this.host = host;
        this.port = port;
        this.timeout = timeout;
    }

    public Result scan(InputStream file) {
        var expired = new AtomicBoolean();
        var currentSocket = new AtomicReference<Socket>();
        long end = System.nanoTime() + timeout.toNanos();
        var deadline = DEADLINES.schedule(() -> {
            expired.set(true);
            Socket socket = currentSocket.get();
            if (socket != null) try { socket.close(); } catch (IOException ignored) { }
        }, timeout.toNanos(), TimeUnit.NANOSECONDS);
        try {
            try (var versionSocket = new Socket()) {
                currentSocket.set(versionSocket);
                if (expired.get()) return Result.TIMEOUT;
                connect(versionSocket, end);
                versionSocket.getOutputStream().write("zVERSION\0".getBytes(StandardCharsets.US_ASCII));
                versionSocket.getOutputStream().flush();
                String version = readRecord(versionSocket.getInputStream());
                if (expired.get()) return Result.TIMEOUT;
                if (!isFresh(version)) return Result.UNAVAILABLE;
            }
            try (var socket = new Socket()) {
                currentSocket.set(socket);
                if (expired.get()) return Result.TIMEOUT;
                connect(socket, end);
                var output = new DataOutputStream(socket.getOutputStream());
                output.write("zINSTREAM\0".getBytes(StandardCharsets.US_ASCII));
                byte[] buffer = new byte[8192];
                long total = 0;
                int count;
                while ((count = file.read(buffer)) != -1) {
                    if (count == 0) continue;
                    total += count;
                    if (total > MAX_BYTES) return Result.UNAVAILABLE;
                    output.writeInt(count);
                    output.write(buffer, 0, count);
                }
                output.writeInt(0);
                output.flush();
                String reply = readRecord(socket.getInputStream());
                if (expired.get()) return Result.TIMEOUT;
                if (reply.equals("stream: OK")) return Result.CLEAN;
                if (reply.startsWith("stream: Heuristics.Limits.Exceeded") && reply.endsWith(" FOUND")) {
                    return Result.SCAN_LIMIT_EXCEEDED;
                }
                if (reply.startsWith("stream: ") && reply.endsWith(" FOUND")) return Result.INFECTED;
                return Result.UNAVAILABLE;
            }
        } catch (SocketTimeoutException ex) {
            return Result.TIMEOUT;
        } catch (IOException ex) {
            return expired.get() ? Result.TIMEOUT : Result.UNAVAILABLE;
        } finally {
            deadline.cancel(false);
        }
    }

    private void connect(Socket socket, long end) throws IOException {
        long remaining = end - System.nanoTime();
        if (remaining <= 0) throw new SocketTimeoutException();
        int millis = (int) Math.min(Integer.MAX_VALUE, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining)));
        socket.connect(new InetSocketAddress(host, port), millis);
        socket.setSoTimeout(millis);
    }

    private static String readRecord(InputStream input) throws IOException {
        var response = new ByteArrayOutputStream();
        int next;
        while ((next = input.read()) != -1 && next != 0 && response.size() < 1024) response.write(next);
        if (next != 0) throw new IOException("Invalid scanner reply");
        return response.toString(StandardCharsets.UTF_8);
    }

    private static boolean isFresh(String version) {
        String[] parts = version.split("/", 3);
        if (parts.length != 3 || !parts[0].startsWith("ClamAV ") || !parts[1].matches("[0-9]+")) return false;
        try {
            // The scanner daemon must run with TZ=UTC: clamd's VERSION date omits a zone.
            Instant updated = LocalDateTime.parse(parts[2].trim().replaceAll(" +", " "), DATABASE_DATE)
                .toInstant(ZoneOffset.UTC);
            Instant now = Instant.now();
            return !updated.isAfter(now.plus(Duration.ofMinutes(5))) && !updated.isBefore(now.minus(MAX_SIGNATURE_AGE));
        } catch (DateTimeParseException ex) {
            return false;
        }
    }
}
