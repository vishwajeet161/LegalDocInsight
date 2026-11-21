package com.legaldocinsight.analysis_service.model;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Selects bounded source passages and checks model-provided evidence identifiers. */
public final class ReportEvidence {
    private ReportEvidence() { }

    public record Selection(Map<String, String> passages, int totalCharacters, int analyzedCharacters) { }

    public static Selection select(String text, int passageLimit, int maxPassages) {
        if (text == null || text.isBlank() || passageLimit < 2 || maxPassages < 1)
            throw new IllegalArgumentException("Invalid passage selection");
        Map<String, String> passages = new LinkedHashMap<>();
        int offset = 0;
        while (offset < text.length() && passages.size() < maxPassages) {
            int end = (int) Math.min((long) text.length(), (long) offset + passageLimit);
            if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                    && Character.isLowSurrogate(text.charAt(end))) end--;
            passages.put("p" + (passages.size() + 1), text.substring(offset, end));
            offset = end;
        }
        return new Selection(Collections.unmodifiableMap(passages), text.length(), offset);
    }

    public static boolean valid(JsonNode report, Selection selection) {
        if (report == null || !report.isObject() || selection == null
                || !finding(report.path("summary"), selection)) return false;
        for (String section : new String[]{"clauses", "obligations", "risks"}) {
            JsonNode items = report.path(section);
            if (!items.isArray()) return false;
            for (JsonNode item : items) if (!finding(item, selection)) return false;
        }
        return report.path("lawObservations").isArray() && report.path("lawObservations").isEmpty();
    }

    private static boolean finding(JsonNode finding, Selection selection) {
        if (!finding.isObject() || !finding.path("text").isTextual()
                || finding.path("text").asText().isBlank() || !finding.path("evidence").isArray()
                || finding.path("evidence").isEmpty()) return false;
        for (JsonNode id : finding.path("evidence"))
            if (!id.isTextual() || !selection.passages().containsKey(id.asText())) return false;
        return true;
    }
}
