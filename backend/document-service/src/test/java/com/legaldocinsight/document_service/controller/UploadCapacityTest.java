package com.legaldocinsight.document_service.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import com.legaldocinsight.document_service.service.DocumentService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class UploadCapacityTest {
    @Test
    void rejectsExcessConcurrentUploadsAndAllowsAnotherAfterRelease() {
        var capacity = new UploadCapacity(1);
        assertTrue(capacity.tryAcquire());
        assertFalse(capacity.tryAcquire());
        capacity.release();
        assertTrue(capacity.tryAcquire());
    }

    @Test
    void controllerReturnsRetryableUnavailableWhenUploadCapacityIsFull() {
        var capacity = new UploadCapacity(1);
        assertTrue(capacity.tryAcquire());
        var controller = new DocumentController(mock(DocumentService.class), capacity);
        var file = new MockMultipartFile("file", "contract.pdf", "application/pdf", new byte[]{1});

        var response = controller.uploadDocument(file);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst("Retry-After"));
    }
}
