package com.distributed.jobplatform.dto;

import java.time.Instant;
import java.util.UUID;

public class JobResultResponse {

    private UUID id;
    private UUID jobId;
    private UUID attemptId;
    private String resultPayload;
    private boolean success;
    private String errorMessage;
    private Instant createdAt;

    public JobResultResponse() {}

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }

    public UUID getAttemptId() { return attemptId; }
    public void setAttemptId(UUID attemptId) { this.attemptId = attemptId; }

    public String getResultPayload() { return resultPayload; }
    public void setResultPayload(String resultPayload) { this.resultPayload = resultPayload; }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
