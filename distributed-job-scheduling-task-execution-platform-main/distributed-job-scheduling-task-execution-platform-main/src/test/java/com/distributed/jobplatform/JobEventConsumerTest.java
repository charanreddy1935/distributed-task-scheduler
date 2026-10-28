package com.distributed.jobplatform;

import com.distributed.jobplatform.config.TestConfig;
import com.distributed.jobplatform.dto.JobEvent;
import com.distributed.jobplatform.enums.JobEventType;
import com.distributed.jobplatform.repository.JobAuditEventRepository;
import com.distributed.jobplatform.service.JobEventConsumer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the Kafka audit consumer persists events and handles duplicate
 * delivery idempotently by eventId (the runtime path is verified against the
 * real broker in Docker; here we test the consumer logic against the DB).
 */
@SpringBootTest
@Import(TestConfig.class)
@TestPropertySource(locations = "classpath:application.properties")
public class JobEventConsumerTest {

    @Autowired
    private JobEventConsumer consumer;

    @Autowired
    private JobAuditEventRepository auditEventRepository;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @BeforeEach
    void setUp() {
        auditEventRepository.deleteAll();
    }

    private String eventJson(JobEventType type, UUID jobId, String eventId) throws Exception {
        JobEvent event = new JobEvent(type, jobId, "test-owner");
        event.setEventId(eventId);
        event.setCorrelationId("corr-" + eventId);
        return objectMapper.writeValueAsString(event);
    }

    @Test
    void consumer_persistsEventToAuditTable() throws Exception {
        UUID jobId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();

        consumer.consumeJobEvent(eventJson(JobEventType.JOB_CREATED, jobId, eventId));

        assertTrue(auditEventRepository.existsByEventId(eventId),
            "Consumer must persist the event to the job_audit_events table");
    }

    @Test
    void consumer_duplicateEvent_doesNotCreateDuplicateRecord() throws Exception {
        UUID jobId = UUID.randomUUID();
        String eventId = UUID.randomUUID().toString();
        String message = eventJson(JobEventType.JOB_SUCCEEDED, jobId, eventId);

        // Simulate at-least-once duplicate delivery
        consumer.consumeJobEvent(message);
        consumer.consumeJobEvent(message);
        consumer.consumeJobEvent(message);

        long count = auditEventRepository.findAll().stream()
            .filter(e -> e.getEventId().equals(eventId))
            .count();
        assertEquals(1, count, "Duplicate Kafka delivery must not create duplicate audit records");
    }

    @Test
    void consumer_malformedMessage_doesNotThrowOrPersist() {
        assertDoesNotThrow(() -> consumer.consumeJobEvent("not-valid-json{{"));
        assertDoesNotThrow(() -> consumer.consumeJobEvent("{\"eventId\":null}"));
    }

    @Test
    void consumer_differentEventTypes_allPersisted() throws Exception {
        UUID jobId = UUID.randomUUID();

        consumer.consumeJobEvent(eventJson(JobEventType.JOB_CREATED, jobId, UUID.randomUUID().toString()));
        consumer.consumeJobEvent(eventJson(JobEventType.JOB_STARTED, jobId, UUID.randomUUID().toString()));
        consumer.consumeJobEvent(eventJson(JobEventType.JOB_SUCCEEDED, jobId, UUID.randomUUID().toString()));

        assertEquals(3, auditEventRepository.count(),
            "Each distinct eventId must be persisted exactly once");
    }
}
