package com.legaldocinsight.document_service.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "test.clamav.host", matches = ".+")
class ClamAvDaemonIntegrationTest {
    @Test
    void detectsAntivirusTestSignatureAndAcceptsSyntheticCleanContent() {
        var scanner = new ClamAvScanner(System.getProperty("test.clamav.host"),
            Integer.parseInt(System.getProperty("test.clamav.port", "3310")), Duration.ofSeconds(20));
        assertEquals(ClamAvScanner.Result.CLEAN, scanner.scan(input("synthetic document text")));
        String eicar = "X5O!P%@AP[4\\PZX54(P^)7CC)7}$" + "EICAR-STANDARD-ANTIVIRUS-TEST-FILE!$H+H*";
        assertEquals(ClamAvScanner.Result.INFECTED, scanner.scan(input(eicar)));
    }

    @Test
    @EnabledIfSystemProperty(named = "test.clamav.limit-check", matches = "true")
    void failsClosedWhenConfiguredScanSizeIsExceeded() throws Exception {
        var archive = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(archive)) {
            zip.putNextEntry(new ZipEntry("synthetic.txt"));
            byte[] block = new byte[64 * 1024];
            for (int index = 0; index < 2049; index++) zip.write(block);
            zip.closeEntry();
        }
        var scanner = new ClamAvScanner(System.getProperty("test.clamav.host"),
            Integer.parseInt(System.getProperty("test.clamav.port", "3310")), Duration.ofSeconds(30));
        assertEquals("SCAN_LIMIT_EXCEEDED", scanner.scan(new ByteArrayInputStream(archive.toByteArray())).name());
    }

    private static ByteArrayInputStream input(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.US_ASCII));
    }
}
