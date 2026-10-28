package com.distributed.jobplatform.service;

import com.distributed.jobplatform.config.KafkaConfig;
import com.distributed.jobplatform.dto.JobEvent;
import com.distributed.jobplatform.entity.JobAuditEvent;
import com.distributed.jobplatform.repository.JobAuditEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Audit consumer for job lifecycle events.
 *
 * Persists every received event into the job_audit_events table.
 * Idempotent by eventId: duplicate Kafka deliveries (at-least-once) are
 * detected and skipped, so duplicates never create duplicate audit rows.
 *
 * Failure isolation: the Kafka listener is outside the job execution path —
 * a consumer failure only affects the audit trail, never job state.
 */
@Service
public class JobEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(JobEventConsumer.class);

    private final ObjectMapper objectMapper;
    private final JobAuditEventRepository auditEventRepository;

    public JobEventConsumer(JobAuditEventRepository auditEventRepository) {
        this.auditEventRepository = auditEventRepository;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @KafkaListener(
        topics = KafkaConfig.JOB_LIFECYCLE_TOPIC,
        groupId = "job-platform-audit-consumer"
    )
    @Transactional
    public void consumeJobEvent(String message) {
        try {
            JobEvent event = objectMapper.readValue(message, JobEvent.class);

            if (event.getEventId() == null || event.getJobId() == null) {
                log.warn("[AUDIT] Skipping malformed event (missing eventId or jobId): {}",
                    summarize(message));
                return;
            }

            // Idempotency: skip events we have already persisted
            if (auditEventRepository.existsByEventId(event.getEventId())) {
                log.debug("[AUDIT] Duplicate event {} for job {} — skipping",
                    event.getEventId(), event.getJobId());
                return;
            }

            JobAuditEvent audit = new JobAuditEvent(
                event.getEventId(),
                event.getEventType(),
                event.getJobId(),
                event.getOwnerId(),
                event.getTimestamp(),
                event.getCorrelationId(),
                event.getMetadata());
            auditEventRepository.save(audit);

            log.info("[AUDIT] Persisted event: {} | JobId: {} | Owner: {} | EventId: {}",
                event.getEventType(), event.getJobId(), event.getOwnerId(), event.getEventId());
        } catch (DataIntegrityViolationException e) {
            // Concurrent duplicate (same eventId inserted concurrently): safe to ignore
            log.debug("[AUDIT] Duplicate event detected via unique constraint — ignoring");
        } catch (Exception e) {
            // Malformed/undeserializable message: log and drop rather than loop forever
            log.error("[AUDIT] Failed to process audit event: {}", e.getMessage());
        }
    }

    private String summarize(String message) {
        return message != null && message.length() > 200
            ? message.substring(0, 200) + "..."
            : message;
    }
}
