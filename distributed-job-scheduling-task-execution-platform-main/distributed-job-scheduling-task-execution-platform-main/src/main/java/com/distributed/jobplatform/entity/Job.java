package com.distributed.jobplatform.entity;

import com.distributed.jobplatform.enums.JobPriority;
import com.distributed.jobplatform.enums.JobStatus;
import com.distributed.jobplatform.enums.JobType;
import jakarta.persistence.*;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "jobs", indexes = {
    @Index(name = "idx_job_status_scheduled", columnList = "status, scheduledAt"),
    @Index(name = "idx_job_owner", columnList = "ownerId"),
    @Index(name = "idx_job_priority", columnList = "priority"),
    @Index(name = "idx_job_idempotency_key", columnList = "idempotencyKey", unique = true)
})
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @NotBlank
    @Column(nullable = false)
    private String ownerId;

    @NotBlank
    @Size(max = 255)
    @Column(nullable = false)
    private String name;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobType type;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobPriority priority = JobPriority.MEDIUM;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status = JobStatus.CREATED;

    @Column(columnDefinition = "TEXT")
    private String payload;

    private Instant scheduledAt;

    private Instant startedAt;

    private Instant completedAt;

    @Column(nullable = false)
    private int retryCount = 0;

    @Column(nullable = false)
    private int maxRetries = 3;

    @Column(nullable = false)
    private long timeoutSeconds = 300;

    @Column(unique = true)
    private String idempotencyKey;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    @Column(nullable = false)
    private Instant updatedAt = Instant.now();

    @Version
    private Long version;

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Job() {}

    public Job(String ownerId, String name, JobType type, JobPriority priority, String payload) {
        this.ownerId = ownerId;
        this.name = name;
        this.type = type;
        this.priority = priority;
        this.payload = payload;
    }

    // Getters and Setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String ownerId) { this.ownerId = ownerId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public JobType getType() { return type; }
    public void setType(JobType type) { this.type = type; }

    public JobPriority getPriority() { return priority; }
    public void setPriority(JobPriority priority) { this.priority = priority; }

    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }

    public Instant getCompletedAt() { return completedAt; }
    public void setCompletedAt(Instant completedAt) { this.completedAt = completedAt; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
}
