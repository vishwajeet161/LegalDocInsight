package com.legaldocinsight.document_service.exception;

public class DocumentAccessDeniedException extends RuntimeException {
    public DocumentAccessDeniedException(String id) {
        super("Access denied to document: " + id);
    }
}
