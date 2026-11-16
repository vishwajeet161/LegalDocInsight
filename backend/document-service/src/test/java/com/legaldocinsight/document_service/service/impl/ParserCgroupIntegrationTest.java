package com.legaldocinsight.document_service.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

@EnabledIfSystemProperty(named = "test.cgroup.root", matches = ".+")
class ParserCgroupIntegrationTest {
    @TempDir Path temporaryDirectory;
    @Test
    void childActuallyRunsInTheConfiguredKernelBoundaryAndTheGroupIsRemoved() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        var parser = new ParserProcess(command(ProbeChild.class), Duration.ofSeconds(10), root);
        var result = parser.extract(new byte[] {1});
        assertTrue(result.isSuccess(), result.getErrorCode());
        assertEquals("verified", result.getText());
        try (var groups = Files.list(root)) {
            assertTrue(groups.noneMatch(path -> path.getFileName().toString().startsWith("legaldoc-parser-")));
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "test.parser.executable-jar", matches = ".+")
    void packagedParserExtractsDocxInsideTheUnprivilegedCgroup() throws Exception {
        byte[] fixture;
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             var bytes = new java.io.ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Synthetic agreement");
            document.write(bytes);
            fixture = bytes.toByteArray();
        }
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx256m", "-Dloader.main=com.legaldocinsight.document_service.service.impl.ParserChildMain",
            "-cp", System.getProperty("test.parser.executable-jar"),
            "org.springframework.boot.loader.launch.PropertiesLauncher");
        var result = new ParserProcess(command, Duration.ofSeconds(20),
            Path.of(System.getProperty("test.cgroup.root"))).extract(fixture);
        assertTrue(result.isSuccess(), result.getErrorCode());
        assertEquals("Synthetic agreement", result.getText());
    }

    @Test
    void processLimitStopsTheChildFromCreatingUnboundedThreads() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        try (var boundary = CgroupV2Boundary.create(root)) {
            Path group;
            try (var groups = Files.list(root)) {
                group = groups.filter(path -> path.getFileName().toString().startsWith("legaldoc-parser-"))
                    .findFirst().orElseThrow();
            }
            Process child = new ProcessBuilder(command(ThreadLimitChild.class))
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try {
                boundary.attach(child);
                var input = new DataOutputStream(child.getOutputStream());
                input.writeInt(1);
                input.write(1);
                input.flush();
                assertTrue(child.waitFor(15, TimeUnit.SECONDS));
                String events = Files.readString(group.resolve("pids.events"));
                assertTrue(events.lines().anyMatch(line -> line.matches("max [1-9][0-9]*")), events);
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void kernelKillsAChildThatExceedsTheMemoryLimit() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        try (var boundary = CgroupV2Boundary.create(root)) {
            Path group;
            try (var groups = Files.list(root)) {
                group = groups.filter(path -> path.getFileName().toString().startsWith("legaldoc-parser-"))
                    .findFirst().orElseThrow();
            }
            var command = new java.util.ArrayList<>(command(MemoryLimitChild.class));
            command.add(1, "-Xmx2g");
            Process child = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                boundary.attach(child);
                child.getOutputStream().write(1);
                child.getOutputStream().flush();
                assertTrue(child.waitFor(20, TimeUnit.SECONDS));
                assertNotEquals(0, child.exitValue());
                String events = Files.readString(group.resolve("memory.events"));
                assertTrue(events.lines().anyMatch(line -> line.matches("oom_kill [1-9][0-9]*")), events);
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void kernelThrottlesCpuWhenTheGroupExceedsItsQuota() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        try (var boundary = CgroupV2Boundary.create(root)) {
            Path group;
            try (var groups = Files.list(root)) {
                group = groups.filter(path -> path.getFileName().toString().startsWith("legaldoc-parser-"))
                    .findFirst().orElseThrow();
            }
            Files.writeString(group.resolve("cpu.max"), "20000 100000", java.nio.file.StandardOpenOption.WRITE);
            Process child = new ProcessBuilder(command(CpuLimitChild.class))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                boundary.attach(child);
                child.getOutputStream().write(1);
                child.getOutputStream().flush();
                assertTrue(child.waitFor(15, TimeUnit.SECONDS));
                assertEquals(0, child.exitValue());
                String events = Files.readString(group.resolve("cpu.stat"));
                assertTrue(events.lines().anyMatch(line -> line.matches("nr_throttled [1-9][0-9]*")), events);
            } finally {
                if (child.isAlive()) child.destroyForcibly();
                child.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void timeoutKillsParserAndItsDescendantAndRemovesTheirCgroup() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        Files.setPosixFilePermissions(temporaryDirectory,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        Path pidFile = temporaryDirectory.resolve("child-pids");
        var command = new java.util.ArrayList<>(command(DescendantChild.class));
        command.add(pidFile.toString());
        var result = new ParserProcess(command, Duration.ofSeconds(3), root).extract(new byte[] {1});
        assertEquals("PARSER_TIMEOUT", result.getErrorCode());
        String[] pids = Files.readString(pidFile).split(" ");
        for (String pid : pids) {
            Path status = Path.of("/proc", pid, "stat");
            if (Files.exists(status)) {
                String contents = Files.readString(status);
                assertEquals('Z', contents.charAt(contents.lastIndexOf(')') + 2));
            }
        }
        try (var groups = Files.list(root)) {
            assertTrue(groups.noneMatch(path -> path.getFileName().toString().startsWith("legaldoc-parser-")));
        }
    }

    @Test
    void parserCannotMoveItselfIntoTheApplicationCgroup() {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        var result = new ParserProcess(command(EscapeChild.class), Duration.ofSeconds(10), root).extract(new byte[] {1});
        assertTrue(result.isSuccess(), result.getErrorCode());
        assertEquals("blocked", result.getText());
    }

    @Test
    void worldWritableDelegationIsRejectedBeforeStartingTheParser() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        var original = Files.getPosixFilePermissions(root);
        try {
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
            var result = new ParserProcess(command(ProbeChild.class), Duration.ofSeconds(10), root).extract(new byte[] {1});
            assertEquals("PARSER_ISOLATION_UNAVAILABLE", result.getErrorCode());
        } finally {
            Files.setPosixFilePermissions(root, original);
        }
    }

    @Test
    void groupWritableDelegationIsRejectedBeforeStartingTheParser() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        var original = Files.getPosixFilePermissions(root);
        try {
            Files.setPosixFilePermissions(root, java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxr-x"));
            var result = new ParserProcess(command(ProbeChild.class), Duration.ofSeconds(10), root).extract(new byte[] {1});
            assertEquals("PARSER_ISOLATION_UNAVAILABLE", result.getErrorCode());
        } finally {
            Files.setPosixFilePermissions(root, original);
        }
    }

    @Test
    void worldWritableDelegationMembershipIsRejectedBeforeStartingTheParser() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        Path membership = root.resolve("cgroup.procs");
        var original = Files.getPosixFilePermissions(membership);
        try {
            Files.setPosixFilePermissions(membership, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
            var result = new ParserProcess(command(ProbeChild.class), Duration.ofSeconds(10), root).extract(new byte[] {1});
            assertEquals("PARSER_ISOLATION_UNAVAILABLE", result.getErrorCode());
        } finally {
            Files.setPosixFilePermissions(membership, original);
        }
    }

    @Test
    void worldWritableAncestorMembershipIsRejectedBeforeStartingTheParser() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        Path membership = root.getParent().resolve("cgroup.procs");
        var original = Files.getPosixFilePermissions(membership);
        try {
            Files.setPosixFilePermissions(membership, java.nio.file.attribute.PosixFilePermissions.fromString("rw-rw-rw-"));
            var result = new ParserProcess(command(ProbeChild.class), Duration.ofSeconds(10), root).extract(new byte[] {1});
            assertEquals("PARSER_ISOLATION_UNAVAILABLE", result.getErrorCode());
        } finally {
            Files.setPosixFilePermissions(membership, original);
        }
    }

    @Test
    void startupRecoveryKillsAndRemovesAStaleParserCgroup() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        var boundary = CgroupV2Boundary.create(root);
        Process child = new ProcessBuilder("sleep", "60").start();
        try {
            boundary.attach(child);
            try {
                var cleanup = CgroupV2Boundary.class.getDeclaredMethod("reapStaleGroups", Path.class);
                cleanup.setAccessible(true);
                cleanup.invoke(null, root);
            } catch (NoSuchMethodException ex) {
                throw new AssertionError("Startup cleanup for interrupted parser groups is missing", ex);
            }
            assertTrue(child.waitFor(5, TimeUnit.SECONDS));
            try (var groups = Files.list(root)) {
                assertTrue(groups.noneMatch(path -> path.getFileName().toString().startsWith("legaldoc-parser-")));
            }
        } finally {
            if (child.isAlive()) child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
            try (var groups = Files.list(root)) {
                if (groups.anyMatch(path -> path.getFileName().toString().startsWith("legaldoc-parser-"))) boundary.close();
            }
        }
    }

    @Test
    void killingTheServiceParentTerminatesItsParserChild() throws Exception {
        Path root = Path.of(System.getProperty("test.cgroup.root"));
        Files.setPosixFilePermissions(temporaryDirectory,
            java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        Path pidFile = temporaryDirectory.resolve("orphan-pid");
        var parentCommand = new java.util.ArrayList<>(command(ParentSpawner.class));
        parentCommand.add(root.toString());
        parentCommand.add(pidFile.toString());
        Process parent = new ProcessBuilder(parentCommand)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
            assertTrue(Files.exists(pidFile) && Files.size(pidFile) > 0);
            long childPid = Long.parseLong(Files.readString(pidFile));
            parent.destroyForcibly();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            Path status = Path.of("/proc", Long.toString(childPid), "stat");
            while (Files.exists(status) && System.nanoTime() < deadline) {
                String value = Files.readString(status);
                if (value.charAt(value.lastIndexOf(')') + 2) == 'Z') break;
                Thread.sleep(10);
            }
            if (Files.exists(status)) {
                String value = Files.readString(status);
                assertEquals('Z', value.charAt(value.lastIndexOf(')') + 2));
            }
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
            parent.waitFor(5, TimeUnit.SECONDS);
            CgroupV2Boundary.reapStaleGroups(root);
        }
    }

    private static List<String> command(Class<?> mainClass) {
        return List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), mainClass.getName());
    }

    public static class ProbeChild {
        public static void main(String[] args) throws Exception {
            var input = new DataInputStream(System.in);
            input.readNBytes(input.readInt());
            String line = Files.readString(Path.of("/proc/self/cgroup")).trim();
            String relative = line.substring(line.indexOf("::") + 2);
            Path group = Path.of("/sys/fs/cgroup").resolve(relative.substring(1));
            if (!Files.readString(group.resolve("memory.max")).trim().equals("536870912")
                    || !Files.readString(group.resolve("memory.swap.max")).trim().equals("0")
                    || !Files.readString(group.resolve("cpu.max")).trim().equals("100000 100000")
                    || !Files.readString(group.resolve("pids.max")).trim().equals("64")) {
                System.exit(3);
            }
            byte[] text = "verified".getBytes(StandardCharsets.UTF_8);
            var output = new DataOutputStream(System.out);
            output.writeInt(0x4c445031);
            output.writeBoolean(true);
            output.writeInt(-1);
            output.writeInt(text.length);
            output.writeInt(text.length);
            output.write(text);
            output.flush();
        }
    }

    public static class ThreadLimitChild {
        public static void main(String[] args) throws Exception {
            var input = new DataInputStream(System.in);
            input.readNBytes(input.readInt());
            var latch = new java.util.concurrent.CountDownLatch(1);
            int count = 0;
            try {
                while (count < 100) {
                    var thread = new Thread(() -> {
                        try { latch.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    });
                    thread.setDaemon(true);
                    thread.start();
                    count++;
                }
            } catch (OutOfMemoryError expected) {
                // Linux reports the pids controller limit as a thread-creation failure.
            }
            if (count >= 100) System.exit(3);
            byte[] text = "limited".getBytes(StandardCharsets.UTF_8);
            var output = new DataOutputStream(System.out);
            output.writeInt(0x4c445031);
            output.writeBoolean(true);
            output.writeInt(-1);
            output.writeInt(text.length);
            output.writeInt(text.length);
            output.write(text);
            output.flush();
        }
    }

    public static class MemoryLimitChild {
        public static void main(String[] args) throws Exception {
            System.in.read();
            var blocks = new java.util.ArrayList<byte[]>();
            while (true) {
                byte[] block = new byte[16 * 1024 * 1024];
                for (int index = 0; index < block.length; index += 4096) block[index] = 1;
                blocks.add(block);
            }
        }
    }

    public static class CpuLimitChild {
        public static void main(String[] args) throws Exception {
            System.in.read();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            var threads = new java.util.ArrayList<Thread>();
            for (int index = 0; index < 4; index++) {
                var thread = new Thread(() -> { while (System.nanoTime() < deadline) Thread.onSpinWait(); });
                threads.add(thread);
                thread.start();
            }
            for (var thread : threads) thread.join();
        }
    }

    public static class DescendantChild {
        public static void main(String[] args) throws Exception {
            var input = new DataInputStream(System.in);
            input.readNBytes(input.readInt());
            Process descendant = new ProcessBuilder("sleep", "60").start();
            Files.writeString(Path.of(args[0]), ProcessHandle.current().pid() + " " + descendant.pid());
            Thread.sleep(60_000);
        }
    }

    public static class EscapeChild {
        public static void main(String[] args) throws Exception {
            var input = new DataInputStream(System.in);
            input.readNBytes(input.readInt());
            String value;
            try {
                Files.writeString(Path.of("/sys/fs/cgroup/legaldoc-app/cgroup.procs"),
                    Long.toString(ProcessHandle.current().pid()), java.nio.file.StandardOpenOption.WRITE);
                value = "escaped";
            } catch (java.io.IOException denied) {
                value = "blocked";
            }
            byte[] text = value.getBytes(StandardCharsets.UTF_8);
            var output = new DataOutputStream(System.out);
            output.writeInt(0x4c445031);
            output.writeBoolean(true);
            output.writeInt(-1);
            output.writeInt(text.length);
            output.writeInt(text.length);
            output.write(text);
            output.flush();
        }
    }

    public static class ParentSpawner {
        public static void main(String[] args) {
            var childCommand = new java.util.ArrayList<>(command(ParserProcessTest.HungChild.class));
            childCommand.add(args[1]);
            new ParserProcess(childCommand, Duration.ofSeconds(60), Path.of(args[0])).extract(new byte[] {1});
        }
    }
}
