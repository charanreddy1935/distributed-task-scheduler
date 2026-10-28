package com.distributed.jobplatform.entity;

import com.distributed.jobplatform.enums.JobEventType;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted audit record of a job lifecycle event received from Kafka.
 *
 * Idempotency: `eventId` is globally unique — the unique constraint makes
 * duplicate Kafka deliveries (at-least-once) harmless. Duplicate consumption
 * is detected via existsByEventId() and silently skipped.
 */
@Entity
@Table(name = "job_audit_events", indexes = {
    @Index(name = "idx_audit_event_event_id", columnList = "eventId", unique = true),
    @Index(name = "idx_audit_event_job_id", columnList = "jobId"),
    @Index(name = "idx_audit_event_received_at", columnList = "receivedAt")
})
public class JobAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /**eventId from the Kafka event — used for idempotent (duplicate-safe) consumption. */
    @Column(nullable = false, unique = true)
    private String eventId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobEventType eventType;

    @Column(nullable = false)
    private UUID jobId;

    private String ownerId;

    private Instant eventTimestamp;

    private String correlationId;

    private String metadata;

    @Column(nullable = false)
    private Instant receivedAt = Instant.now();

    public JobAuditEvent() {}

    public JobAuditEvent(String eventId, JobEventType eventType, UUID jobId, String ownerId,
                         Instant eventTimestamp, String correlationId, String metadata) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.jobId = jobId;
        this.ownerId = ownerId;
        this.eventTimestamp = eventTimestamp;
        this.correlationId = correlationId;
        this.metadata = metadata;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public JobEventType getEventType() { return eventType; }
    public void setEventType(JobEventType eventType) { this.eventType = eventType; }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public Instant getEventTimestamp() { return eventTimestamp; }
    public void setEventTimestamp(Instant eventTimestamp) { this.eventTimestamp = eventTimestamp; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }

    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
}
