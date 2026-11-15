package com.legaldocinsight.document_service.service.impl;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParserProcessTest {
    @TempDir Path temporaryDirectory;

    @Test
    void extractsTextThroughAChildProcess() throws Exception {
        byte[] fixture;
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Synthetic agreement");
            document.write(bytes);
            fixture = bytes.toByteArray();
        }
        var result = extract(command("com.legaldocinsight.document_service.service.impl.ParserChildMain"),
            Duration.ofSeconds(10), fixture);
        assertTrue(result.isSuccess());
        assertEquals("Synthetic agreement", result.getText());
        assertEquals(19, result.getCharacterCount());
    }

    @Test
    @EnabledIfSystemProperty(named = "test.parser.executable-jar", matches = ".+")
    void extractsThroughTheSpringBootExecutableJarLauncher() throws Exception {
        byte[] fixture;
        try (var document = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Synthetic agreement");
            document.write(bytes);
            fixture = bytes.toByteArray();
        }
        var command = List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Dloader.main=com.legaldocinsight.document_service.service.impl.ParserChildMain",
            "-cp", System.getProperty("test.parser.executable-jar"),
            "org.springframework.boot.loader.launch.PropertiesLauncher");
        var result = extract(command, Duration.ofSeconds(15), fixture);
        assertTrue(result.isSuccess());
        assertEquals("Synthetic agreement", result.getText());
    }

    @Test
    void timeoutKillsAndReapsAChildThatDoesNotReadItsInput() throws Exception {
        Path pidFile = temporaryDirectory.resolve("pid");
        var command = command(HungChild.class.getName());
        command.add(pidFile.toString());
        long start = System.nanoTime();
        var result = extract(command, Duration.ofSeconds(2), new byte[10 * 1024 * 1024]);
        assertFalse(result.isSuccess());
        assertEquals("PARSER_TIMEOUT", result.getErrorCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 8);
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void oversizedChildResponseIsRejectedAndTheChildIsStopped() throws Exception {
        Path pidFile = temporaryDirectory.resolve("oversized-pid");
        var command = command(OversizedChild.class.getName());
        command.add(pidFile.toString());
        long start = System.nanoTime();
        var result = extract(command, Duration.ofSeconds(5), new byte[] {1});
        assertFalse(result.isSuccess());
        assertEquals("PARSER_CRASH", result.getErrorCode());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toSeconds() < 4);
        long pid = Long.parseLong(Files.readString(pidFile));
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    @Test
    void requiredIsolationFailsClosedWhenCgroupIsUnavailable() throws Exception {
        Path marker = temporaryDirectory.resolve("child-started");
        var command = command(HungChild.class.getName());
        command.add(marker.toString());
        TikaExtractionService.ExtractionResult result;
        try {
            var constructor = ParserProcess.class.getConstructor(List.class, Duration.class, Path.class);
            result = constructor.newInstance(command, Duration.ofSeconds(2), temporaryDirectory).extract(new byte[] {1});
        } catch (NoSuchMethodException ex) {
            throw new AssertionError("The required cgroup boundary is not implemented", ex);
        }
        assertFalse(result.isSuccess());
        assertEquals("PARSER_ISOLATION_UNAVAILABLE", result.getErrorCode());
        assertFalse(Files.exists(marker));
    }

    @Test
    void cancellationReapsTheParserBeforeReturning() throws Exception {
        Path pidFile = temporaryDirectory.resolve("cancelled-pid");
        var command = command(HungChild.class.getName());
        command.add(pidFile.toString());
        var result = new java.util.concurrent.atomic.AtomicReference<TikaExtractionService.ExtractionResult>();
        var worker = new Thread(() -> result.set(new ParserProcess(command, Duration.ofSeconds(30)).extract(new byte[1024])));
        worker.start();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while ((!Files.exists(pidFile) || Files.size(pidFile) == 0) && System.nanoTime() < deadline) Thread.sleep(10);
        assertTrue(Files.exists(pidFile) && Files.size(pidFile) > 0);
        long pid = Long.parseLong(Files.readString(pidFile));
        worker.interrupt();
        worker.join(5000);
        assertFalse(worker.isAlive());
        assertEquals("PARSER_CANCELLED", result.get().getErrorCode());
        assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false));
    }

    private static TikaExtractionService.ExtractionResult extract(List<String> command, Duration timeout, byte[] input)
            throws Exception {
        return new ParserProcess(command, timeout).extract(input);
    }

    private static java.util.ArrayList<String> command(String mainClass) {
        return new java.util.ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"), mainClass));
    }

    public static class HungChild {
        public static void main(String[] args) throws Exception {
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            Thread.sleep(60_000);
        }
    }

    public static class OversizedChild {
        public static void main(String[] args) throws Exception {
            var input = new java.io.DataInputStream(System.in);
            input.readNBytes(input.readInt());
            Files.writeString(Path.of(args[0]), Long.toString(ProcessHandle.current().pid()));
            var output = new java.io.DataOutputStream(System.out);
            output.writeInt(0x4c445031);
            output.writeBoolean(true);
            output.writeInt(1);
            output.writeInt(1);
            output.writeInt(4_000_001);
            output.flush();
            Thread.sleep(60_000);
        }
    }
}
