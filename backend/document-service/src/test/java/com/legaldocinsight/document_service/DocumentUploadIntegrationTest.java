package com.legaldocinsight.document_service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.legaldocinsight.document_service.service.impl.ExtractionOrchestrator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;

@SpringBootTest(properties = {
    "spring.datasource.url=jdbc:h2:mem:documenttest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "spring.datasource.driver-class-name=org.h2.Driver",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "storage.local.upload-dir=${java.io.tmpdir}/legaldocinsight-integration-test"
})
@AutoConfigureMockMvc
class DocumentUploadIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockitoBean ExtractionOrchestrator extraction;

    @Test
    void uploadedDocumentRequiresItsAccessTokenEvenWithForgedUserHeader() throws Exception {
        var file = new MockMultipartFile("file", "contract.pdf", MediaType.APPLICATION_PDF_VALUE,
            "%PDF-1.4\n%%EOF".getBytes(StandardCharsets.US_ASCII));
        var response = mvc.perform(multipart("/api/documents/upload").file(file))
            .andExpect(status().isAccepted()).andExpect(header().string("Cache-Control", "no-store"))
            .andReturn().getResponse();
        var body = json.readTree(response.getContentAsString());
        var id = body.get("documentId").asText();
        var token = body.get("accessToken").asText();

        mvc.perform(get("/api/documents/{id}", id).header("X-User-Id", "anonymous"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/{id}", id).header("X-Document-Token", "wrong"))
            .andExpect(status().isForbidden());
        mvc.perform(get("/api/documents/{id}", id).header("X-Document-Token", token))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        mvc.perform(delete("/api/documents/{id}", id).header("X-User-Id", "anonymous"))
            .andExpect(status().isForbidden());
        mvc.perform(delete("/api/documents/{id}", id).header("X-Document-Token", token))
            .andExpect(status().isNoContent());
    }

    @Test
    void acceptsARealDocxDocument() throws Exception {
        byte[] bytes;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("Synthetic agreement");
            document.write(output);
            bytes = output.toByteArray();
        }
        var file = new MockMultipartFile("file", "contract.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document", bytes);
        mvc.perform(multipart("/api/documents/upload").file(file)).andExpect(status().isAccepted());
    }
}
