package com.legaldocinsight.document_service.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@EnabledIfSystemProperty(named = "test.sandbox.root", matches = ".+")
class ParserSandboxIntegrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void sandboxBlocksReadableHostFileAndReachableLocalService() throws Exception {
        Files.setPosixFilePermissions(temporaryDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path secret = temporaryDirectory.resolve("synthetic-host-file.txt");
        Files.writeString(secret, "synthetic host data");
        Files.setPosixFilePermissions(secret, PosixFilePermissions.fromString("rw-r--r--"));
        Path jar = probeJar();
        try (var server = new ServerSocket(0)) {
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", jar.toString(), ProbeChild.class.getName(), secret.toString(), Integer.toString(server.getLocalPort()));
            Path root = Path.of(System.getProperty("test.sandbox.root"));
            var baseline = new ParserProcess(command, Duration.ofSeconds(10), root).extract(new byte[] {1});
            assertTrue(baseline.isSuccess(), baseline.getErrorCode());
            assertEquals("file-and-network", baseline.getText());

            ParserProcess confined;
            try {
                confined = ParserProcess.class.getConstructor(List.class, Duration.class, Path.class, Path.class)
                    .newInstance(command, Duration.ofSeconds(10), root, jar);
            } catch (NoSuchMethodException ex) {
                fail("Parser filesystem and network confinement is unavailable");
                return;
            }
            var result = confined.extract(new byte[] {1});
            assertTrue(result.isSuccess(), result.getErrorCode());
            assertEquals("blocked", result.getText());
        }
    }

    @Test
    void timedOutSandboxIsKilledAndItsCgroupRemoved() throws Exception {
        Files.setPosixFilePermissions(temporaryDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path jar = probeJar();
        Path root = Path.of(System.getProperty("test.sandbox.root"));
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", jar.toString(), ProbeChild.class.getName(), "hang");
        var parser = new ParserProcess(command, Duration.ofSeconds(1), root, jar);
        assertEquals("PARSER_TIMEOUT", parser.extract(new byte[] {1}).getErrorCode());
        try (var groups = Files.list(root)) {
            assertTrue(groups.noneMatch(path -> path.getFileName().toString().startsWith("legaldoc-parser-")));
        }
    }

    @org.junit.jupiter.api.RepeatedTest(30)
    void killingTheServiceParentTerminatesTheSandboxedParser() throws Exception {
        Files.setPosixFilePermissions(temporaryDirectory, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path jar = probeJar();
        Path root = Path.of(System.getProperty("test.sandbox.root"));
        var parent = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), ParentSpawner.class.getName(), root.toString(), jar.toString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            Path group = null;
            while (System.nanoTime() < deadline) {
                try (var groups = Files.list(root)) {
                    group = groups.filter(path -> path.getFileName().toString().startsWith("legaldoc-parser-"))
                        .findFirst().orElse(null);
                }
                if (group != null && Files.readAllLines(group.resolve("cgroup.procs")).size() >= 2) break;
                Thread.sleep(10);
            }
            assertTrue(group != null && Files.readAllLines(group.resolve("cgroup.procs")).size() >= 2,
                "Sandbox did not start");
            parent.destroyForcibly();
            assertTrue(parent.waitFor(5, TimeUnit.SECONDS));
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (Files.readString(group.resolve("cgroup.events")).contains("populated 1")
                    && System.nanoTime() < deadline) Thread.sleep(10);
            var processes = new StringBuilder();
            for (String pid : Files.readAllLines(group.resolve("cgroup.procs"))) {
                Path status = Path.of("/proc", pid, "status");
                if (Files.exists(status)) {
                    for (String line : Files.readAllLines(status)) {
                        if (line.startsWith("Name:") || line.startsWith("State:") || line.startsWith("PPid:")) {
                            processes.append(line).append(' ');
                        }
                    }
                }
            }
            assertTrue(Files.readString(group.resolve("cgroup.events")).contains("populated 0"), processes.toString());
        } finally {
            if (parent.isAlive()) parent.destroyForcibly();
            parent.waitFor(5, TimeUnit.SECONDS);
            CgroupV2Boundary.reapStaleGroups(root);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "test.parser.executable-jar", matches = ".+")
    void packagedParserExtractsSyntheticPdfAndDocxInSandbox() throws Exception {
        var pdfBytes = new java.io.ByteArrayOutputStream();
        try (var pdf = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            pdf.addPage(page);
            try (var stream = new org.apache.pdfbox.pdmodel.PDPageContentStream(pdf, page)) {
                stream.beginText();
                stream.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                    org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 700);
                stream.showText("Synthetic agreement");
                stream.endText();
            }
            pdf.save(pdfBytes);
        }
        var docxBytes = new java.io.ByteArrayOutputStream();
        try (var document = new org.apache.poi.xwpf.usermodel.XWPFDocument()) {
            document.createParagraph().createRun().setText("Synthetic agreement");
            document.write(docxBytes);
        }
        Path jar = Path.of(System.getProperty("test.parser.executable-jar")).toAbsolutePath();
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xmx256m", "-Dloader.main=com.legaldocinsight.document_service.service.impl.ParserChildMain",
            "-cp", jar.toString(), "org.springframework.boot.loader.launch.PropertiesLauncher");
        var parser = new ParserProcess(command, Duration.ofSeconds(20),
            Path.of(System.getProperty("test.sandbox.root")), jar);
        var pdf = parser.extract(pdfBytes.toByteArray());
        assertTrue(pdf.isSuccess(), pdf.getErrorCode());
        assertEquals("Synthetic agreement", pdf.getText());
        assertEquals(1, pdf.getPageCount());
        var docx = parser.extract(docxBytes.toByteArray());
        assertTrue(docx.isSuccess(), docx.getErrorCode());
        assertEquals("Synthetic agreement", docx.getText());
    }

    private Path probeJar() throws Exception {
        Path jar = temporaryDirectory.resolve("probe.jar");
        String entry = ProbeChild.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(jar));
             InputStream input = ProbeChild.class.getClassLoader().getResourceAsStream(entry)) {
            if (input == null) throw new IllegalStateException("Missing synthetic probe class");
            output.putNextEntry(new JarEntry(entry));
            input.transferTo(output);
            output.closeEntry();
        }
        Files.setPosixFilePermissions(jar, PosixFilePermissions.fromString("rw-r--r--"));
        return jar;
    }

    public static class ProbeChild {
        public static void main(String[] args) throws Exception {
            var input = new DataInputStream(System.in);
            input.readNBytes(input.readInt());
            if (args[0].equals("hang")) {
                Thread.sleep(60_000);
                return;
            }
            boolean fileAccessible = Files.isReadable(Path.of(args[0]));
            boolean networkAccessible;
            try (var socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", Integer.parseInt(args[1])), 1000);
                networkAccessible = true;
            } catch (java.io.IOException denied) {
                networkAccessible = false;
            }
            String value = fileAccessible && networkAccessible ? "file-and-network"
                : fileAccessible ? "file" : networkAccessible ? "network" : "blocked";
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
            Path jar = Path.of(args[1]);
            var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", jar.toString(), ProbeChild.class.getName(), "hang");
            new ParserProcess(command, Duration.ofSeconds(60), Path.of(args[0]), jar).extract(new byte[] {1});
        }
    }
}
