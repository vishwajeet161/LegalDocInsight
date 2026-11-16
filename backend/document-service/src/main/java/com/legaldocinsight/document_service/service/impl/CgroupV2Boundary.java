package com.legaldocinsight.document_service.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** A per-child cgroup under a pre-delegated cgroup-v2 directory. */
final class CgroupV2Boundary implements AutoCloseable {
    private final Path directory;

    private CgroupV2Boundary(Path directory) {
        this.directory = directory;
    }

    static CgroupV2Boundary create(Path delegatedRoot) throws IOException {
        Path root = validateRoot(delegatedRoot);
        Path directory = Files.createDirectory(root.resolve("legaldoc-parser-" + UUID.randomUUID()));
        try {
            write(directory.resolve("memory.max"), "536870912");
            write(directory.resolve("memory.swap.max"), "0");
            write(directory.resolve("memory.oom.group"), "1");
            write(directory.resolve("cpu.max"), "100000 100000");
            write(directory.resolve("pids.max"), "64");
            if (!Files.isWritable(directory.resolve("cgroup.kill"))) throw new IOException("cgroup.kill unavailable");
            return new CgroupV2Boundary(directory);
        } catch (IOException | RuntimeException ex) {
            Files.deleteIfExists(directory);
            throw ex;
        }
    }

    static void reapStaleGroups(Path delegatedRoot) throws IOException {
        Path root = validateRoot(delegatedRoot);
        try (var children = Files.list(root)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                if (name.matches("legaldoc-parser-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                        && Files.isDirectory(child, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    new CgroupV2Boundary(child).close();
                }
            }
        }
    }

    private static Path validateRoot(Path delegatedRoot) throws IOException {
        Path root = delegatedRoot.toRealPath();
        if (!System.getProperty("os.name").equalsIgnoreCase("Linux")
                || !Files.getFileStore(root).type().equals("cgroup2") || !Files.isWritable(root)) {
            throw new IOException("A writable cgroup v2 delegation is required");
        }
        for (Path ancestor = root; ancestor != null && Files.getFileStore(ancestor).type().equals("cgroup2");
                ancestor = ancestor.getParent()) {
            requireRestrictedPermissions(ancestor);
            requireRestrictedPermissions(ancestor.resolve("cgroup.procs"));
        }
        return root;
    }

    private static void requireRestrictedPermissions(Path path) throws IOException {
        int owner = ((Number) Files.getAttribute(path, "unix:uid")).intValue();
        int mode = ((Number) Files.getAttribute(path, "unix:mode")).intValue();
        // Reject group writes conservatively, including effective POSIX ACL grants.
        if (owner == 65534 || (mode & 0022) != 0) {
            throw new IOException("Parser cgroup permissions are not restricted");
        }
    }

    void attach(Process process) throws IOException {
        write(directory.resolve("cgroup.procs"), Long.toString(process.pid()));
        if (Files.readAllLines(directory.resolve("cgroup.procs")).stream()
                .noneMatch(line -> line.trim().equals(Long.toString(process.pid())))) {
            throw new IOException("Parser process did not enter its cgroup");
        }
    }

    @Override
    public void close() throws IOException {
        write(directory.resolve("cgroup.kill"), "1");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (Files.readString(directory.resolve("cgroup.events")).contains("populated 1")) {
            if (System.nanoTime() >= deadline) throw new IOException("Parser cgroup did not become empty");
            try {
                Thread.sleep(10);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while cleaning parser cgroup", ex);
            }
        }
        Files.delete(directory);
    }

    private static void write(Path file, String value) throws IOException {
        Files.writeString(file, value, StandardOpenOption.WRITE);
    }
}
