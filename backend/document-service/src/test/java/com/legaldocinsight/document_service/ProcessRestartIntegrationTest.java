package com.legaldocinsight.document_service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.h2.tools.Server;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.DataInputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in process crash test; H2 and the scanner are deliberately test substitutes. */
@EnabledIfSystemProperty(named = "test.process.restart", matches = "true")
class ProcessRestartIntegrationTest {
    @TempDir Path temp;

    @Test
    void committedProcessingIsRecoveredAfterTheServiceIsKilledDuringScan() throws Exception {
        var pdf = temp.resolve("synthetic.pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Synthetic agreement");
                content.endText();
            }
            document.save(pdf.toFile());
        }
        var database = Server.createTcpServer("-tcpPort", "0", "-ifNotExists").start();
        String url = "jdbc:h2:tcp://localhost:" + database.getPort() + "/mem:restart;MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        var firstScan = new CountDownLatch(1);
        var scans = new AtomicInteger();
        Process service = null;
        try (var scanner = new ServerSocket(0)) {
            var scannerThread = new Thread(() -> {
                try {
                    while (!scanner.isClosed()) {
                        try (var socket = scanner.accept()) {
                            var input = new DataInputStream(socket.getInputStream());
                            var command = new StringBuilder();
                            int b;
                            while ((b = input.read()) > 0) command.append((char) b);
                            if (command.toString().equals("zVERSION")) {
                                String date = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US));
                                socket.getOutputStream().write(("ClamAV test/1/" + date + "\0").getBytes(StandardCharsets.US_ASCII));
                            } else if (command.toString().equals("zINSTREAM")) {
                                int size;
                                while ((size = input.readInt()) != 0) input.skipNBytes(size);
                                if (scans.incrementAndGet() == 1) {
                                    firstScan.countDown();
                                    while (input.read() != -1) { }
                                } else {
                                    socket.getOutputStream().write("stream: OK\0".getBytes(StandardCharsets.US_ASCII));
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    if (!scanner.isClosed()) throw new RuntimeException(ex);
                }
            }, "controlled-scanner");
            scannerThread.setDaemon(true);
            scannerThread.start();

            service = startService(url, scanner.getLocalPort(), "first.log");
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() -> {
                try (var connection = DriverManager.getConnection(url, "sa", "");
                     var query = connection.createStatement();
                     var result = query.executeQuery("select count(*) from documents")) {
                    return result.next();
                }
            });
            try (var connection = DriverManager.getConnection(url, "sa", "");
                 var insert = connection.prepareStatement("insert into documents (id, original_file_name, stored_file_name, file_path, file_size, file_type, status, created_at) values (?, ?, ?, ?, ?, ?, 'PENDING', current_timestamp)")) {
                insert.setString(1, "restart-test");
                insert.setString(2, "synthetic.pdf");
                insert.setString(3, "synthetic.pdf");
                insert.setString(4, pdf.toString());
                insert.setLong(5, Files.size(pdf));
                insert.setString(6, "application/pdf");
                insert.executeUpdate();
            }
            assertTrue(firstScan.await(60, TimeUnit.SECONDS), "service did not begin the controlled scan");
            assertEquals("PROCESSING", status(url));
            service.destroyForcibly();
            assertTrue(service.waitFor(10, TimeUnit.SECONDS));
            assertEquals("PROCESSING", status(url), "the killed process must not have finished the job");

            service = startService(url, scanner.getLocalPort(), "second.log");
            await().atMost(Duration.ofSeconds(90)).ignoreExceptions().untilAsserted(() ->
                assertEquals("EXTRACTED", status(url)));
            try (var connection = DriverManager.getConnection(url, "sa", "");
                 var query = connection.createStatement();
                 var result = query.executeQuery("select extracted_text from documents where id='restart-test'")) {
                assertTrue(result.next());
                assertTrue(result.getString(1).contains("Synthetic agreement"));
            }
            assertEquals(2, scans.get());
            service.destroyForcibly();
            assertTrue(service.waitFor(10, TimeUnit.SECONDS));
            service = startService(url, scanner.getLocalPort(), "third.log");
            await().atMost(Duration.ofSeconds(60)).ignoreExceptions().until(() ->
                Files.readString(temp.resolve("third.log")).contains("Started DocumentServiceApplication"));
            await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertEquals("EXTRACTED", status(url));
                assertEquals(2, scans.get(), "terminal work must not be rescanned after restart");
            });
        } finally {
            if (service != null && service.isAlive()) {
                service.destroyForcibly();
                service.waitFor(10, TimeUnit.SECONDS);
            }
            database.stop();
        }
    }

    private Process startService(String url, int scannerPort, String log) throws Exception {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("surefire.test.class.path"), DocumentServiceApplication.class.getName(),
            "--spring.main.web-application-type=none", "--spring.datasource.url=" + url,
            "--spring.datasource.username=sa", "--spring.datasource.password=", "--spring.datasource.driver-class-name=org.h2.Driver",
            "--spring.jpa.hibernate.ddl-auto=update", "--storage.local.upload-dir=" + temp,
            "--document.scanner.port=" + scannerPort, "--document.scanner.timeout-ms=90000",
            "--document.extraction.recovery-delay-ms=100")
            .redirectErrorStream(true).redirectOutput(temp.resolve(log).toFile()).start();
    }

    private String status(String url) throws Exception {
        try (var connection = DriverManager.getConnection(url, "sa", "");
             var query = connection.createStatement();
             var result = query.executeQuery("select status from documents where id='restart-test'")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }
}
