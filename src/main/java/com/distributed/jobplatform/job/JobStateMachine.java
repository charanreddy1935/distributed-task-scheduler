package com.distributed.jobplatform.job;

import com.distributed.jobplatform.enums.JobStatus;
import com.distributed.jobplatform.exception.InvalidJobTransitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Centralized state machine for job status transitions.
 * All state transitions MUST go through this class.
 * 
 * Valid transitions:
 *   CREATED -> SCHEDULED, QUEUED, CANCELLED
 *   SCHEDULED -> QUEUED, CANCELLED
 *   QUEUED -> RUNNING, CANCELLED
 *   RUNNING -> SUCCESS, FAILED, RETRYING
 *   FAILED -> RETRYING, DEAD
 *   RETRYING -> QUEUED
 *   SUCCESS -> (terminal)
 *   DEAD -> (terminal, only admin recovery)
 *   CANCELLED -> (terminal)
 */
@Component
public class JobStateMachine {

    private static final Logger log = LoggerFactory.getLogger(JobStateMachine.class);

    private static final Map<JobStatus, Set<JobStatus>> VALID_TRANSITIONS;

    static {
        Map<JobStatus, Set<JobStatus>> transitions = new EnumMap<>(JobStatus.class);
        transitions.put(JobStatus.CREATED, Set.of(JobStatus.SCHEDULED, JobStatus.QUEUED, JobStatus.CANCELLED));
        transitions.put(JobStatus.SCHEDULED, Set.of(JobStatus.QUEUED, JobStatus.CANCELLED));
        transitions.put(JobStatus.QUEUED, Set.of(JobStatus.RUNNING, JobStatus.CANCELLED));
        transitions.put(JobStatus.RUNNING, Set.of(JobStatus.SUCCESS, JobStatus.FAILED, JobStatus.RETRYING));
        transitions.put(JobStatus.FAILED, Set.of(JobStatus.RETRYING, JobStatus.DEAD));
        transitions.put(JobStatus.RETRYING, Set.of(JobStatus.QUEUED));
        transitions.put(JobStatus.SUCCESS, Set.of()); // terminal
        transitions.put(JobStatus.DEAD, Set.of()); // terminal (admin recovery creates new attempt)
        transitions.put(JobStatus.CANCELLED, Set.of()); // terminal
        VALID_TRANSITIONS = Collections.unmodifiableMap(transitions);
    }

    /**
     * Validate a state transition. Throws InvalidJobTransitionException if invalid.
     */
    public void validateTransition(JobStatus currentStatus, JobStatus targetStatus) {
        Set<JobStatus> allowed = VALID_TRANSITIONS.get(currentStatus);
        if (allowed == null || !allowed.contains(targetStatus)) {
            log.warn("Invalid state transition attempted: {} -> {}", currentStatus, targetStatus);
            throw new InvalidJobTransitionException(
                String.format("Invalid state transition: %s -> %s. Allowed transitions from %s: %s",
                    currentStatus, targetStatus, currentStatus, allowed));
        }
        log.debug("Valid state transition: {} -> {}", currentStatus, targetStatus);
    }

    /**
     * Check if a transition is valid without throwing.
     */
    public boolean isValidTransition(JobStatus currentStatus, JobStatus targetStatus) {
        Set<JobStatus> allowed = VALID_TRANSITIONS.get(currentStatus);
        return allowed != null && allowed.contains(targetStatus);
    }

    /**
     * Get all valid transitions from a given status.
     */
    public Set<JobStatus> getValidTransitions(JobStatus currentStatus) {
        return VALID_TRANSITIONS.getOrDefault(currentStatus, Set.of());
    }

    /**
     * Check if a status is terminal (no further transitions except admin recovery).
     */
    public boolean isTerminal(JobStatus status) {
        return VALID_TRANSITIONS.getOrDefault(status, Set.of()).isEmpty();
    }

    /**
     * Recover a DEAD job back to QUEUED (admin-only operation).
     * This is a special transition that bypasses the normal state machine.
     */
    public void validateRecovery(JobStatus currentStatus) {
        if (currentStatus != JobStatus.DEAD) {
            throw new InvalidJobTransitionException(
                "Can only recover DEAD jobs. Current status: " + currentStatus);
        }
    }
}
