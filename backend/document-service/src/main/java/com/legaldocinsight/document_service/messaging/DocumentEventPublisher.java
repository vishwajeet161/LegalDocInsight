package com.legaldocinsight.document_service.messaging;

import com.legaldocinsight.document_service.messaging.event.DocumentExtractedEvent;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;

import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class DocumentEventPublisher {

    private final KafkaTemplate<String, DocumentExtractedEvent> kafkaTemplate;

    @Value("${kafka.topics.document-extracted}")
    private String topic;

    public DocumentEventPublisher(KafkaTemplate<String, DocumentExtractedEvent> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    public void publishDocumentExtracted(DocumentExtractedEvent event) {
        String correlationId = MDC.get("correlationId"); // propagate trace from MdcFilter

        Message<DocumentExtractedEvent> message = MessageBuilder
                .withPayload(event)
                .setHeader(KafkaHeaders.TOPIC, topic)
                .setHeader(KafkaHeaders.KEY, event.documentId()) // partition by documentId
                .setHeader("correlationId", correlationId != null ? correlationId : "")
                .setHeader("eventType", "DOCUMENT_EXTRACTED")
                .build();

        kafkaTemplate.send(message)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish DocumentExtractedEvent for documentId={} correlationId={}",
                                event.documentId(), correlationId, ex);
                    } else {
                        log.info("Published DocumentExtractedEvent documentId={} partition={} offset={} correlationId={} topic={}",
                                event.documentId(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset(),
                                correlationId,
                                topic
                            );
                    }
                });
    }
}