package com.legaldocinsight.document_service.service.impl;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Runs one parser child with bounded input, output and elapsed time. */
public final class ParserProcess {
    private static final int MAGIC = 0x4c445031;
    private static final int MAX_INPUT = 10 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 4_000_000;
    private static final Set<String> SAFE_FAILURES = Set.of("EMPTY_TEXT", "EXTRACTION_FAILED", "INPUT_LIMIT_EXCEEDED", "UNSAFE_ARCHIVE", "MALFORMED_DOCUMENT", "ENCRYPTED_DOCUMENT", "OUTPUT_LIMIT_EXCEEDED");
    private final List<String> command;
    private final Duration timeout;
    private final Path cgroupRoot;
    private final Path sandboxJar;

    public ParserProcess(List<String> command, Duration timeout) {
        this(command, timeout, null);
    }

    public ParserProcess(List<String> command, Duration timeout, Path cgroupRoot) {
        this(command, timeout, cgroupRoot, null);
    }

    public ParserProcess(List<String> command, Duration timeout, Path cgroupRoot, Path sandboxJar) {
        if (command.isEmpty() || timeout.isNegative() || timeout.isZero()) throw new IllegalArgumentException("Invalid parser configuration");
        if (sandboxJar != null && (cgroupRoot == null || !sandboxJar.isAbsolute()
                || !command.contains(sandboxJar.toString()))) throw new IllegalArgumentException("Invalid parser sandbox configuration");
        this.command = List.copyOf(command);
        this.timeout = timeout;
        this.cgroupRoot = cgroupRoot;
        this.sandboxJar = sandboxJar;
    }

    public TikaExtractionService.ExtractionResult extract(byte[] input) {
        if (input.length > MAX_INPUT) return failure("INPUT_LIMIT_EXCEEDED");
        if (sandboxJar != null && (!Files.isExecutable(Path.of("/usr/bin/bwrap")) || !Files.isRegularFile(sandboxJar))) {
            return failure("PARSER_ISOLATION_UNAVAILABLE");
        }
        CgroupV2Boundary boundary = null;
        if (cgroupRoot != null) {
            try {
                boundary = CgroupV2Boundary.create(cgroupRoot);
            } catch (IOException | RuntimeException ex) {
                return failure("PARSER_ISOLATION_UNAVAILABLE");
            }
        }
        Process process = null;
        ExecutorService pipes = Executors.newFixedThreadPool(sandboxJar == null ? 2 : 3, task -> {
            Thread thread = new Thread(task, "parser-pipe");
            thread.setDaemon(true);
            return thread;
        });
        long deadline = System.nanoTime() + timeout.toNanos();
        try {
            var childCommand = new ArrayList<String>();
            if (sandboxJar != null) {
                // The gate prevents forks outside the cgroup. A separate watchdog holds
                // the service's stderr pipe and kills the group if the service dies,
                // including while bubblewrap is still installing parent-death handlers.
                String gate = "killfile=$1; shift; IFS= read -r gate || exit 1; "
                    + "(trap '' PIPE; while printf x >&2 2>/dev/null; do /bin/sleep 0.2; done; "
                    + "printf 1 > \"$killfile\") & exec \"$@\" 2>/dev/null";
                childCommand.addAll(List.of("/bin/sh", "-c", gate, "parser-gate", boundary.killFile().toString()));
            }
            if (boundary != null) {
                // Keep the parser from moving itself out of its cgroup or inheriting
                // the service's privileges if untrusted input compromises it.
                childCommand.addAll(List.of("/usr/bin/setpriv", "--reuid=65534", "--regid=65534",
                    "--clear-groups", "--inh-caps=-all", "--ambient-caps=-all",
                    "--bounding-set=-all", "--no-new-privs"));
                childCommand.add("--pdeathsig=KILL");
            }
            if (sandboxJar != null) {
                String javaHome = System.getProperty("java.home");
                childCommand.addAll(List.of("/usr/bin/bwrap", "--unshare-user", "--unshare-net", "--unshare-pid",
                    "--unshare-ipc", "--unshare-uts", "--disable-userns", "--cap-drop", "ALL", "--die-with-parent",
                    "--ro-bind", "/usr/lib", "/usr/lib", "--ro-bind-try", "/usr/lib64", "/usr/lib64",
                    "--symlink", "usr/lib", "/lib", "--symlink", "usr/lib64", "/lib64",
                    "--ro-bind", javaHome, javaHome, "--ro-bind", sandboxJar.toString(), "/parser.jar",
                    "--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--chdir", "/tmp", "--clearenv", "--"));
                for (String part : command) childCommand.add(part.equals(sandboxJar.toString()) ? "/parser.jar" : part);
            } else {
                childCommand.addAll(command);
            }
            var builder = new ProcessBuilder(childCommand);
            if (sandboxJar == null) builder.redirectError(ProcessBuilder.Redirect.DISCARD);
            builder.environment().clear();
            process = builder.start();
            Process child = process;
            if (sandboxJar != null) pipes.submit(() -> {
                try (var errors = child.getErrorStream()) { errors.transferTo(OutputStream.nullOutputStream()); }
                return null;
            });
            if (boundary != null) {
                try {
                    boundary.attach(child);
                } catch (IOException ex) {
                    return failure("PARSER_ISOLATION_UNAVAILABLE");
                }
            }
            var writer = pipes.submit(() -> {
                try (var output = new DataOutputStream(child.getOutputStream())) {
                    if (sandboxJar != null) output.writeByte('\n');
                    output.writeInt(input.length);
                    output.write(input);
                }
                return null;
            });
            var reader = pipes.submit(() -> readResponse(child));
            writer.get(remaining(deadline), TimeUnit.NANOSECONDS);
            var result = reader.get(remaining(deadline), TimeUnit.NANOSECONDS);
            if ("PARSER_CRASH".equals(result.getErrorCode())) return result;
            if (!child.waitFor(remaining(deadline), TimeUnit.NANOSECONDS)) throw new TimeoutException();
            return child.exitValue() == 0 ? result : failure("PARSER_CRASH");
        } catch (TimeoutException ex) {
            return failure("PARSER_TIMEOUT");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failure("PARSER_CANCELLED");
        } catch (IOException | ExecutionException ex) {
            return failure("PARSER_CRASH");
        } finally {
            // Complete cleanup even when cancellation interrupted the worker.
            boolean restoreInterrupt = Thread.interrupted();
            try {
                if (process != null) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    if (process.isAlive()) process.destroyForcibly();
                    long cleanupDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while (process.isAlive() && System.nanoTime() < cleanupDeadline) {
                        try {
                            process.waitFor(Math.max(1, cleanupDeadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                        } catch (InterruptedException ex) {
                            restoreInterrupt = true;
                        }
                    }
                }
                pipes.shutdownNow();
                if (boundary != null) {
                    try {
                        boundary.close();
                    } catch (IOException ex) {
                        throw new IllegalStateException("Could not clean the parser cgroup", ex);
                    }
                }
            } finally {
                if (restoreInterrupt) Thread.currentThread().interrupt();
            }
        }
    }

    public void reapStaleChildren() {
        if (cgroupRoot == null) return;
        try {
            CgroupV2Boundary.reapStaleGroups(cgroupRoot);
        } catch (IOException | RuntimeException ex) {
            throw new IllegalStateException("Could not clean interrupted parser cgroups", ex);
        }
    }

    private static long remaining(long deadline) throws TimeoutException {
        long value = deadline - System.nanoTime();
        if (value <= 0) throw new TimeoutException();
        return value;
    }

    private static TikaExtractionService.ExtractionResult readResponse(Process child) throws IOException {
        try (var input = new DataInputStream(child.getInputStream())) {
            if (input.readInt() != MAGIC) return failure("PARSER_CRASH");
            if (!input.readBoolean()) {
                String code = input.readUTF();
                return failure(SAFE_FAILURES.contains(code) ? code : "PARSER_CRASH");
            }
            int pages = input.readInt();
            int characters = input.readInt();
            int length = input.readInt();
            if ((pages != -1 && pages <= 0) || characters <= 0 || characters > 1_000_000
                    || length < 1 || length > MAX_TEXT_BYTES) return failure("PARSER_CRASH");
            byte[] bytes = input.readNBytes(length);
            if (bytes.length != length) return failure("PARSER_CRASH");
            String text = new String(bytes, StandardCharsets.UTF_8);
            if (text.isBlank() || text.length() != characters) return failure("PARSER_CRASH");
            return TikaExtractionService.ExtractionResult.builder().success(true).text(text)
                .characterCount(characters).pageCount(pages == -1 ? null : pages).build();
        }
    }

    private static TikaExtractionService.ExtractionResult failure(String code) {
        return TikaExtractionService.ExtractionResult.builder().success(false).errorCode(code).build();
    }
}
