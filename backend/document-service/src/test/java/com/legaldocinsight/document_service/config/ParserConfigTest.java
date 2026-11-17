package com.legaldocinsight.document_service.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class ParserConfigTest {
    @Test
    void configuredIsolationRequiresAPackagedParserJar() {
        assertThrows(IllegalStateException.class,
            () -> new ParserConfig().parserProcess(30_000, "/synthetic/cgroup"));
    }
}
