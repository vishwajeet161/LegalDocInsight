package com.legaldocinsight.analysis_service.controller;

import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/analysis")
public class AnalysisController {

    @PostMapping("/analyze")
    public String analyze(@RequestBody Map<String, String> request) {

        String documentId = request.get("documentId");
        String text = request.get("text");

        System.out.println("Analyzing document: " + documentId);
        System.out.println("Text length: " + text.length());

        return "Analysis started";
    }
}