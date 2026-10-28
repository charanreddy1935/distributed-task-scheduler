package com.distributed.jobplatform.enums;

/**
 * Job status enumeration representing the complete state machine.
 *
 * State machine:
 *   CREATED -> SCHEDULED -> QUEUED -> RUNNING -> SUCCESS
 *   RUNNING -> FAILED -> RETRYING -> QUEUED
 *   FAILED -> DEAD (when retries exhausted)
 *   CREATED/SCHEDULED/QUEUED -> CANCELLED
 */
public enum JobStatus {
    CREATED,
    SCHEDULED,
    QUEUED,
    RUNNING,
    SUCCESS,
    FAILED,
    RETRYING,
    DEAD,
    CANCELLED
}
