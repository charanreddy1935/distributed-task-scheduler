package com.distributed.jobplatform;

import com.distributed.jobplatform.enums.JobStatus;
import com.distributed.jobplatform.exception.InvalidJobTransitionException;
import com.distributed.jobplatform.job.JobStateMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class JobStateMachineTest {

    private JobStateMachine stateMachine;

    @BeforeEach
    void setUp() {
        stateMachine = new JobStateMachine();
    }

    // ===========================
    // Valid Transitions
    // ===========================

    @Test
    void validTransition_created_to_scheduled() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.CREATED, JobStatus.SCHEDULED));
    }

    @Test
    void validTransition_created_to_queued() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.CREATED, JobStatus.QUEUED));
    }

    @Test
    void validTransition_created_to_cancelled() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.CREATED, JobStatus.CANCELLED));
    }

    @Test
    void validTransition_scheduled_to_queued() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.SCHEDULED, JobStatus.QUEUED));
    }

    @Test
    void validTransition_scheduled_to_cancelled() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.SCHEDULED, JobStatus.CANCELLED));
    }

    @Test
    void validTransition_queued_to_running() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.QUEUED, JobStatus.RUNNING));
    }

    @Test
    void validTransition_queued_to_cancelled() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.QUEUED, JobStatus.CANCELLED));
    }

    @Test
    void validTransition_running_to_success() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.RUNNING, JobStatus.SUCCESS));
    }

    @Test
    void validTransition_running_to_failed() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.RUNNING, JobStatus.FAILED));
    }

    @Test
    void validTransition_running_to_retrying() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.RUNNING, JobStatus.RETRYING));
    }

    @Test
    void validTransition_failed_to_retrying() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.FAILED, JobStatus.RETRYING));
    }

    @Test
    void validTransition_failed_to_dead() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.FAILED, JobStatus.DEAD));
    }

    @Test
    void validTransition_retrying_to_queued() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.RETRYING, JobStatus.QUEUED));
    }

    // ===========================
    // Invalid Transitions
    // ===========================

    @Test
    void invalidTransition_created_to_running() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateTransition(JobStatus.CREATED, JobStatus.RUNNING));
    }

    @Test
    void invalidTransition_created_to_success() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateTransition(JobStatus.CREATED, JobStatus.SUCCESS));
    }

    @Test
    void invalidTransition_success_to_anything() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateTransition(JobStatus.SUCCESS, JobStatus.QUEUED));
    }

    @Test
    void invalidTransition_dead_to_anything() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateTransition(JobStatus.DEAD, JobStatus.QUEUED));
    }

    @Test
    void invalidTransition_cancelled_to_anything() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateTransition(JobStatus.CANCELLED, JobStatus.QUEUED));
    }

    @Test
    void invalidTransition_queued_to_success() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateTransition(JobStatus.QUEUED, JobStatus.SUCCESS));
    }

    @Test
    void invalidTransition_running_to_queued() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateTransition(JobStatus.RUNNING, JobStatus.QUEUED));
    }

    @Test
    void invalidTransition_retrying_to_running() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateTransition(JobStatus.RETRYING, JobStatus.RUNNING));
    }

    // ===========================
    // Terminal Status Tests
    // ===========================

    @Test
    void isTerminal_success() {
        assertTrue(stateMachine.isTerminal(JobStatus.SUCCESS));
    }

    @Test
    void isTerminal_dead() {
        assertTrue(stateMachine.isTerminal(JobStatus.DEAD));
    }

    @Test
    void isTerminal_cancelled() {
        assertTrue(stateMachine.isTerminal(JobStatus.CANCELLED));
    }

    @Test
    void isTerminal_created() {
        assertFalse(stateMachine.isTerminal(JobStatus.CREATED));
    }

    @Test
    void isTerminal_running() {
        assertFalse(stateMachine.isTerminal(JobStatus.RUNNING));
    }

    // ===========================
    // Valid Transitions Set Tests
    // ===========================

    @Test
    void validTransitions_created_has_correct_set() {
        var transitions = stateMachine.getValidTransitions(JobStatus.CREATED);
        assertEquals(3, transitions.size());
        assertTrue(transitions.contains(JobStatus.SCHEDULED));
        assertTrue(transitions.contains(JobStatus.QUEUED));
        assertTrue(transitions.contains(JobStatus.CANCELLED));
    }

    @Test
    void validTransitions_success_is_empty() {
        var transitions = stateMachine.getValidTransitions(JobStatus.SUCCESS);
        assertTrue(transitions.isEmpty());
    }

    // ===========================
    // Recovery Tests
    // ===========================

    @Test
    void validRecovery_from_dead() {
        assertDoesNotThrow(() -> stateMachine.validateRecovery(JobStatus.DEAD));
    }

    @Test
    void invalidRecovery_from_non_dead() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateRecovery(JobStatus.FAILED));
    }

    @Test
    void invalidRecovery_from_success() {
        assertThrows(InvalidJobTransitionException.class,
            () -> stateMachine.validateRecovery(JobStatus.SUCCESS));
    }

    // ===========================
    // Full Happy Path Test
    // ===========================

    @Test
    void fullHappyPath_created_to_success() {
        // CREATED -> SCHEDULED -> QUEUED -> RUNNING -> SUCCESS
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.CREATED, JobStatus.SCHEDULED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.SCHEDULED, JobStatus.QUEUED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.QUEUED, JobStatus.RUNNING));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.RUNNING, JobStatus.SUCCESS));
        assertTrue(stateMachine.isTerminal(JobStatus.SUCCESS));
    }

    // ===========================
    // Full Failure Path Test
    // ===========================

    @Test
    void fullFailurePath_created_to_dead() {
        // CREATED -> QUEUED -> RUNNING -> FAILED -> RETRYING -> QUEUED -> RUNNING -> FAILED -> DEAD
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.CREATED, JobStatus.QUEUED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.QUEUED, JobStatus.RUNNING));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.RUNNING, JobStatus.FAILED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.FAILED, JobStatus.RETRYING));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.RETRYING, JobStatus.QUEUED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.QUEUED, JobStatus.RUNNING));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.RUNNING, JobStatus.FAILED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.FAILED, JobStatus.DEAD));
        assertTrue(stateMachine.isTerminal(JobStatus.DEAD));
    }

    // ===========================
    // Cancellation Path Test
    // ===========================

    @Test
    void cancellationPath() {
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.CREATED, JobStatus.SCHEDULED));
        assertDoesNotThrow(() -> stateMachine.validateTransition(JobStatus.SCHEDULED, JobStatus.CANCELLED));
        assertTrue(stateMachine.isTerminal(JobStatus.CANCELLED));
    }

    // ===========================
    // isValidTransition Tests
    // ===========================

    @Test
    void isValidTransition_returns_true_for_valid() {
        assertTrue(stateMachine.isValidTransition(JobStatus.CREATED, JobStatus.QUEUED));
    }

    @Test
    void isValidTransition_returns_false_for_invalid() {
        assertFalse(stateMachine.isValidTransition(JobStatus.CREATED, JobStatus.RUNNING));
    }
}
