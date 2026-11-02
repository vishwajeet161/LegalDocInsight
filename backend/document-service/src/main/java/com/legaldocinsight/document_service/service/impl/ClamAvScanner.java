package com.legaldocinsight.document_service.service.impl;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A bounded clamd INSTREAM client. The caller must provide a bounded local file stream. */
public class ClamAvScanner {
    public enum Result { CLEAN, INFECTED, UNAVAILABLE, TIMEOUT }

    private static final ScheduledExecutorService DEADLINES = Executors.newSingleThreadScheduledExecutor(task -> {
        var thread = new Thread(task, "scanner-deadlines");
        thread.setDaemon(true);
        return thread;
    });
    private static final long MAX_BYTES = 10L * 1024 * 1024;
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
        try (var socket = new Socket()) {
            var deadline = DEADLINES.schedule(() -> {
                expired.set(true);
                try { socket.close(); } catch (IOException ignored) { }
            }, timeout.toMillis(), TimeUnit.MILLISECONDS);
            try {
                socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
                socket.setSoTimeout((int) timeout.toMillis());
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
                var response = new ByteArrayOutputStream();
                var input = socket.getInputStream();
                int next;
                while ((next = input.read()) != -1 && next != 0 && response.size() < 1024) {
                    response.write(next);
                }
                if (expired.get()) return Result.TIMEOUT;
                if (next != 0) return Result.UNAVAILABLE;
                String reply = response.toString(StandardCharsets.UTF_8);
                if (reply.equals("stream: OK")) return Result.CLEAN;
                if (reply.startsWith("stream: ") && reply.endsWith(" FOUND")) return Result.INFECTED;
                return Result.UNAVAILABLE;
            } catch (IOException ex) {
                return expired.get() ? Result.TIMEOUT : Result.UNAVAILABLE;
            } finally {
                deadline.cancel(false);
            }
        } catch (IOException ex) {
            return expired.get() ? Result.TIMEOUT : Result.UNAVAILABLE;
        }
    }
}
