package com.distributed.jobplatform.dto;

import com.distributed.jobplatform.enums.JobEventType;
import java.time.Instant;
import java.util.UUID;

public class JobEvent {

    private String eventId;
    private JobEventType eventType;
    private UUID jobId;
    private String ownerId;
    private Instant timestamp;
    private String correlationId;
    private String metadata;

    public JobEvent() {
        this.eventId = UUID.randomUUID().toString();
        this.timestamp = Instant.now();
    }

    public JobEvent(JobEventType eventType, UUID jobId, String ownerId) {
        this();
        this.eventType = eventType;
        this.jobId = jobId;
        this.ownerId = ownerId;
    }

    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }

    public JobEventType getEventType() { return eventType; }
    public void setEventType(JobEventType eventType) { this.eventType = eventType; }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

    public String getMetadata() { return metadata; }
    public void setMetadata(String metadata) { this.metadata = metadata; }
}
