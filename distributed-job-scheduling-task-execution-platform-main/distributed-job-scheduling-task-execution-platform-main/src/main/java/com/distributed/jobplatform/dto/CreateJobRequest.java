package com.distributed.jobplatform.dto;

import com.distributed.jobplatform.enums.JobPriority;
import com.distributed.jobplatform.enums.JobType;
import jakarta.validation.constraints.*;
import java.time.Instant;

public class CreateJobRequest {

    @NotBlank(message = "Job name is required")
    @Size(max = 255, message = "Job name must not exceed 255 characters")
    private String name;

    @NotNull(message = "Job type is required")
    private JobType type;

    private JobPriority priority = JobPriority.MEDIUM;

    @Size(max = 10000, message = "Payload must not exceed 10000 characters")
    private String payload;

    private Instant scheduledAt;

    @Min(value = 1, message = "Max retries must be at least 1")
    @Max(value = 10, message = "Max retries must not exceed 10")
    private int maxRetries = 3;

    @Min(value = 1, message = "Timeout must be at least 1 second")
    @Max(value = 3600, message = "Timeout must not exceed 3600 seconds")
    private long timeoutSeconds = 300;

    private String idempotencyKey;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public JobType getType() { return type; }
    public void setType(JobType type) { this.type = type; }

    public JobPriority getPriority() { return priority; }
    public void setPriority(JobPriority priority) { this.priority = priority; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public Instant getScheduledAt() { return scheduledAt; }
    public void setScheduledAt(Instant scheduledAt) { this.scheduledAt = scheduledAt; }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int maxRetries) { this.maxRetries = maxRetries; }

    public long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }
}
