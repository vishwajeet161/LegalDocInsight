package com.legaldocinsight.analysis_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.analysis_service.model.ReportEvidence;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ReportEvidenceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void recordsCoverageAndStablePassageIds() {
        var selection = ReportEvidence.select("abcdefghij", 4, 2);
        assertEquals(10, selection.totalCharacters());
        assertEquals(8, selection.analyzedCharacters());
        assertEquals("abcd", selection.passages().get("p1"));
        assertEquals("efgh", selection.passages().get("p2"));
    }

    @Test void rejectsUnknownAndMissingEvidenceAndLawObservations() throws Exception {
        var selection = ReportEvidence.select("Synthetic agreement", 100, 1);
        var valid = json.readTree("{\"summary\":{\"text\":\"Summary\",\"evidence\":[\"p1\"]},\"clauses\":[],\"obligations\":[],\"risks\":[],\"lawObservations\":[]}");
        assertTrue(ReportEvidence.valid(valid, selection));
        var unknown = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) unknown.path("summary").path("evidence")).set(0, json.getNodeFactory().textNode("p9"));
        assertFalse(ReportEvidence.valid(unknown, selection));
        var missing = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) missing.path("summary").path("evidence")).removeAll();
        assertFalse(ReportEvidence.valid(missing, selection));
        var law = valid.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) law.path("lawObservations")).add("unsupported law");
        assertFalse(ReportEvidence.valid(law, selection));
    }
}
