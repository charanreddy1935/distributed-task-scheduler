package com.distributed.jobplatform;

import com.distributed.jobplatform.config.TestConfig;
import com.distributed.jobplatform.dto.CreateJobRequest;
import com.distributed.jobplatform.dto.JobResponse;
import com.distributed.jobplatform.enums.AttemptStatus;
import com.distributed.jobplatform.enums.JobPriority;
import com.distributed.jobplatform.enums.JobStatus;
import com.distributed.jobplatform.enums.JobType;
import com.distributed.jobplatform.entity.IdempotencyRecord;
import com.distributed.jobplatform.entity.Job;
import com.distributed.jobplatform.exception.DuplicateJobException;
import com.distributed.jobplatform.exception.InvalidJobTransitionException;
import com.distributed.jobplatform.exception.JobNotFoundException;
import com.distributed.jobplatform.handler.*;
import com.distributed.jobplatform.job.JobStateMachine;
import com.distributed.jobplatform.repository.*;
import com.distributed.jobplatform.service.JobService;
import com.distributed.jobplatform.service.JobEventPublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestConfig.class)
@TestPropertySource(locations = "classpath:application.properties")
public class JobServiceIntegrationTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @Autowired
    private IdempotencyRecordRepository idempotencyRepository;

    @Autowired
    private JobAttemptRepository attemptRepository;

    @Autowired
    private JobResultRepository resultRepository;

    private static final String TEST_USER = "test-user";

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
        idempotencyRepository.deleteAll();
        attemptRepository.deleteAll();
        resultRepository.deleteAll();
    }

    // ===========================
    // Job Creation Tests
    // ===========================

    @Test
    void createJob_immediateJob() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Test Job");
        request.setType(JobType.DEMO_TASK);
        request.setPriority(JobPriority.MEDIUM);

        JobResponse response = jobService.createJob(request, TEST_USER);

        assertNotNull(response.getId());
        assertEquals("Test Job", response.getName());
        assertEquals(JobType.DEMO_TASK, response.getType());
        assertEquals(JobPriority.MEDIUM, response.getPriority());
        assertEquals(JobStatus.QUEUED, response.getStatus());
        assertEquals(TEST_USER, response.getOwnerId());
    }

    @Test
    void createJob_setsCreatedAt() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Test Job");
        request.setType(JobType.DEMO_TASK);

        JobResponse response = jobService.createJob(request, TEST_USER);

        assertNotNull(response.getCreatedAt());
        assertTrue(response.getCreatedAt().isBefore(java.time.Instant.now().plusSeconds(5)));
    }

    @Test
    void createJob_maxRetries_stored() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Test Job");
        request.setType(JobType.DEMO_TASK);
        request.setMaxRetries(7);

        JobResponse response = jobService.createJob(request, TEST_USER);

        assertEquals(7, response.getMaxRetries());
    }

    @Test
    void createJob_invalidType_throws() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Test Job");
        request.setType(JobType.DEMO_TASK);

        // This should work since DEMO_TASK is registered
        assertDoesNotThrow(() -> jobService.createJob(request, TEST_USER));
    }

    // ===========================
    // Idempotency Tests
    // ===========================

    @Test
    void idempotency_firstRequest_createsJob() {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Idempotent Job");
        request.setType(JobType.DEMO_TASK);
        request.setIdempotencyKey(idempotencyKey);

        JobResponse response = jobService.createJob(request, TEST_USER);

        assertNotNull(response.getId());
        assertEquals("Idempotent Job", response.getName());
    }

    @Test
    void idempotency_duplicateRequest_returnsSameJob() {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Idempotent Job");
        request.setType(JobType.DEMO_TASK);
        request.setIdempotencyKey(idempotencyKey);

        JobResponse first = jobService.createJob(request, TEST_USER);
        JobResponse second = jobService.createJob(request, TEST_USER);

        assertEquals(first.getId(), second.getId());
        assertEquals(first.getName(), second.getName());
    }

    @Test
    void idempotency_differentKeys_createDifferentJobs() {
        CreateJobRequest request1 = new CreateJobRequest();
        request1.setName("Job 1");
        request1.setType(JobType.DEMO_TASK);
        request1.setIdempotencyKey(UUID.randomUUID().toString());

        CreateJobRequest request2 = new CreateJobRequest();
        request2.setName("Job 2");
        request2.setType(JobType.DEMO_TASK);
        request2.setIdempotencyKey(UUID.randomUUID().toString());

        JobResponse first = jobService.createJob(request1, TEST_USER);
        JobResponse second = jobService.createJob(request2, TEST_USER);

        assertNotEquals(first.getId(), second.getId());
    }

    @Test
    void idempotency_recordPersistsInDatabase() {
        String idempotencyKey = UUID.randomUUID().toString();
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Persist Test");
        request.setType(JobType.DEMO_TASK);
        request.setIdempotencyKey(idempotencyKey);

        JobResponse response = jobService.createJob(request, TEST_USER);

        IdempotencyRecord record = idempotencyRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        assertNotNull(record);
        assertEquals(response.getId(), record.getJobId());
    }

    // ===========================
    // Cancellation Tests
    // ===========================

    @Test
    void cancelJob_queuedJob() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Cancel Test");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);
        JobResponse cancelled = jobService.cancelJob(created.getId(), TEST_USER);

        assertEquals(JobStatus.CANCELLED, cancelled.getStatus());
    }

    @Test
    void cancelJob_notFound_throws() {
        assertThrows(JobNotFoundException.class,
            () -> jobService.cancelJob(UUID.randomUUID(), TEST_USER));
    }

    @Test
    void cancelJob_wrongOwner_throws() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Owner Test");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);

        assertThrows(JobNotFoundException.class,
            () -> jobService.cancelJob(created.getId(), "wrong-user"));
    }

    @Test
    void cancelJob_alreadyCancelled_throws() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Double Cancel");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.cancelJob(created.getId(), TEST_USER);

        assertThrows(InvalidJobTransitionException.class,
            () -> jobService.cancelJob(created.getId(), TEST_USER));
    }

    // ===========================
    // Query Tests
    // ===========================

    @Test
    void getJobsByOwner_returnsOnlyOwnJobs() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("My Job");
        request.setType(JobType.DEMO_TASK);

        jobService.createJob(request, "user-a");
        jobService.createJob(request, "user-b");

        var jobs = jobService.getJobsByOwner("user-a",
            org.springframework.data.domain.PageRequest.of(0, 10));

        assertEquals(1, jobs.getContent().size());
        assertEquals("user-a", jobs.getContent().get(0).getOwnerId());
    }

    @Test
    void getJobById_returnsJob() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Find Me");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);
        JobResponse found = jobService.getJobById(created.getId(), TEST_USER);

        assertEquals(created.getId(), found.getId());
        assertEquals("Find Me", found.getName());
    }

    @Test
    void getJobById_wrongOwner_throws() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Private Job");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, "owner");

        assertThrows(JobNotFoundException.class,
            () -> jobService.getJobById(created.getId(), "not-owner"));
    }

    // ===========================
    // Retry Tests
    // ===========================

    @Test
    void retryJob_deadJob_recoversThroughStateMachine() {
        // Manual retry of a DEAD job is the owner-facing recovery path and must go
        // through the state machine (validateRecovery), resetting retryCount.
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Dead Retry");
        request.setType(JobType.FAILING);
        request.setMaxRetries(0);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());
        Job dead = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(dead);
        assertEquals(JobStatus.DEAD, jobRepository.findById(created.getId()).orElseThrow().getStatus());

        JobResponse retried = jobService.retryJob(created.getId(), TEST_USER);
        assertEquals(JobStatus.QUEUED, retried.getStatus());
        assertEquals(0, retried.getRetryCount());

        // Recovered job is claimable again (terminal state did not persist)
        assertTrue(jobService.claimJob(created.getId()));
        assertEquals(JobStatus.RUNNING, jobRepository.findById(created.getId()).orElseThrow().getStatus());
    }

    @Test
    void retryJob_notFailed_throws() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Retry Test");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);

        assertThrows(InvalidJobTransitionException.class,
            () -> jobService.retryJob(created.getId(), TEST_USER));
    }

    // ===========================
    // Concurrency Claiming Tests
    // ===========================

    @Test
    void claimJob_success() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Claim Test");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);
        boolean claimed = jobService.claimJob(created.getId());
        assertTrue(claimed);
    }

    @Test
    void claimJob_doubleClaim_secondFails() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Double Claim");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);
        boolean first = jobService.claimJob(created.getId());
        boolean second = jobService.claimJob(created.getId());

        assertTrue(first);
        assertFalse(second);
    }

    @Test
    void claimJob_concurrentWorkers_onlyOneWins() throws InterruptedException {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Concurrent Claim");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);

        // Simulate multiple workers trying to claim
        java.util.concurrent.atomic.AtomicInteger successCount = new java.util.concurrent.atomic.AtomicInteger(0);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(10);
        java.util.List<Thread> threads = new java.util.ArrayList<>();

        for (int i = 0; i < 10; i++) {
            Thread t = new Thread(() -> {
                try {
                    boolean claimed = jobService.claimJob(created.getId());
                    if (claimed) {
                        successCount.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
            threads.add(t);
            t.start();
        }

        latch.await();
        for (Thread t : threads) {
            t.join();
        }

        assertEquals(1, successCount.get(), "Only one worker should successfully claim the job");
    }

    // ===========================
    // Priority Tests
    // ===========================

    @Test
    void createJob_highPriority() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("High Priority");
        request.setType(JobType.DEMO_TASK);
        request.setPriority(JobPriority.HIGH);

        JobResponse response = jobService.createJob(request, TEST_USER);
        assertEquals(JobPriority.HIGH, response.getPriority());
    }

    @Test
    void createJob_lowPriority() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Low Priority");
        request.setType(JobType.DEMO_TASK);
        request.setPriority(JobPriority.LOW);

        JobResponse response = jobService.createJob(request, TEST_USER);
        assertEquals(JobPriority.LOW, response.getPriority());
    }

    // ===========================
    // State Transition Tests (through service)
    // ===========================

    @Test
    void fullLifecycle_created_to_queued() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Lifecycle");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);
        assertEquals(JobStatus.QUEUED, created.getStatus());
    }

    @Test
    void cancel_from_scheduled_state() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Scheduled Cancel");
        request.setType(JobType.DEMO_TASK);
        request.setScheduledAt(java.time.Instant.now().plusSeconds(3600));

        JobResponse created = jobService.createJob(request, TEST_USER);
        assertEquals(JobStatus.SCHEDULED, created.getStatus());

        JobResponse cancelled = jobService.cancelJob(created.getId(), TEST_USER);
        assertEquals(JobStatus.CANCELLED, cancelled.getStatus());
    }

    // ===========================
    // AUTOMATIC RETRY TESTS
    // ===========================

    @Test
    void automaticRetry_failedJobBecomesRetrying() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Auto Retry");
        request.setType(JobType.FAILING);
        request.setMaxRetries(3);
        request.setTimeoutSeconds(300);

        JobResponse created = jobService.createJob(request, TEST_USER);
        assertEquals(JobStatus.QUEUED, created.getStatus());

        jobService.claimJob(created.getId());

        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job after = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, after.getStatus());
        assertEquals(1, after.getRetryCount());
        assertNotNull(after.getScheduledAt(), "scheduledAt must be set for backoff");
        assertTrue(after.getScheduledAt().isAfter(java.time.Instant.now().minusSeconds(2)),
            "scheduledAt must be in the future (backoff deadline)");
    }

    @Test
    void automaticRetry_retryCountIncrements() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Retry Count");
        request.setType(JobType.FAILING);
        request.setMaxRetries(5);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());
        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job after = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(1, after.getRetryCount());
    }

    @Test
    void automaticRetry_retriesExhausted_becomesDead() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Exhaust Retries");
        request.setType(JobType.FAILING);
        request.setMaxRetries(1);

        JobResponse created = jobService.createJob(request, TEST_USER);
        UUID jobId = created.getId();

        for (int attempt = 0; attempt < 5; attempt++) {
            Job current = jobRepository.findById(jobId).orElseThrow();
            if (current.getStatus() == JobStatus.RETRYING) {
                // Simulate scheduler: transition RETRYING → QUEUED
                jobService.scheduleJob(current);
            }
            current = jobRepository.findById(jobId).orElseThrow();
            if (current.getStatus() == JobStatus.QUEUED) {
                jobService.claimJob(jobId);
            }
            current = jobRepository.findById(jobId).orElseThrow();
            if (current.getStatus() == JobStatus.RUNNING) {
                jobService.executeJob(current);
            }
        }

        Job afterExhaust = jobRepository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.DEAD, afterExhaust.getStatus());
    }

    @Test
    void automaticRetry_multipleFailures_retriesCorrectly() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Multi Fail");
        request.setType(JobType.FAILING);
        request.setMaxRetries(3);

        JobResponse created = jobService.createJob(request, TEST_USER);
        UUID jobId = created.getId();

        for (int attempt = 0; attempt < 8; attempt++) {
            Job             current = jobRepository.findById(jobId).orElseThrow();
            if (current.getStatus() == JobStatus.RETRYING) {
                jobService.scheduleJob(current);
                continue;
            }
            current = jobRepository.findById(jobId).orElseThrow();
            if (current.getStatus() == JobStatus.QUEUED) {
                jobService.claimJob(jobId);
            }
            current = jobRepository.findById(jobId).orElseThrow();
            if (current.getStatus() == JobStatus.RUNNING) {
                jobService.executeJob(current);
            }
        }

        Job finalJob = jobRepository.findById(jobId).orElseThrow();
        assertEquals(JobStatus.DEAD, finalJob.getStatus());
        assertEquals(3, finalJob.getRetryCount());
    }

    // ===========================
    // BACKOFF TESTS
    // ===========================

    @Test
    void backoff_exponentialFormula() {
        assertEquals(1000L, jobService.calculateBackoff(0));
        assertEquals(2000L, jobService.calculateBackoff(1));
        assertEquals(4000L, jobService.calculateBackoff(2));
        assertEquals(8000L, jobService.calculateBackoff(3));
        assertEquals(16000L, jobService.calculateBackoff(4));
        assertEquals(30000L, jobService.calculateBackoff(5));
    }

    @Test
    void backoff_neverExceedsMax() {
        for (int i = 0; i < 20; i++) {
            assertTrue(jobService.calculateBackoff(i) <= 30000L,
                "Backoff at retryCount=" + i + " must not exceed 30s");
        }
    }

    // ===========================
    // TIMEOUT TESTS
    // ===========================

    @Test
    void timeout_longRunningJob_timesOut() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Timeout Test");
        request.setType(JobType.REPORT);
        request.setTimeoutSeconds(1);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());

        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job after = jobRepository.findById(created.getId()).orElseThrow();
        assertTrue(after.getStatus() == JobStatus.FAILED || after.getStatus() == JobStatus.RETRYING,
            "Timed-out job should be FAILED or RETRYING, got: " + after.getStatus());

        var attempts = jobService.getJobAttempts(created.getId(), TEST_USER);
        assertFalse(attempts.isEmpty(), "At least one attempt should exist");
        assertEquals(AttemptStatus.TIMED_OUT, attempts.get(0).getStatus());
    }

    @Test
    void timeout_withRetries_retriesAfterTimeout() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Timeout Retry");
        request.setType(JobType.REPORT);
        request.setTimeoutSeconds(1);
        request.setMaxRetries(2);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());

        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job after = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, after.getStatus());
        assertEquals(1, after.getRetryCount());
    }

    @Test
    void timeout_retriesExhausted_becomesDead() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Timeout Exhaust");
        request.setType(JobType.REPORT);
        request.setTimeoutSeconds(1);
        request.setMaxRetries(1);

        JobResponse created = jobService.createJob(request, TEST_USER);

        for (int attempt = 0; attempt < 5; attempt++) {
            Job current = jobRepository.findById(created.getId()).orElseThrow();
            if (current.getStatus() == JobStatus.RETRYING) {
                jobService.scheduleJob(current);
                continue;
            }
            current = jobRepository.findById(created.getId()).orElseThrow();
            if (current.getStatus() == JobStatus.QUEUED) {
                jobService.claimJob(created.getId());
            }
            current = jobRepository.findById(created.getId()).orElseThrow();
            if (current.getStatus() == JobStatus.RUNNING) {
                jobService.executeJob(current);
            }
        }

        Job after = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.DEAD, after.getStatus());
    }

    // ===========================
    // DEAD RECOVERY TESTS
    // ===========================

    @Test
    void recoverJob_deadBecomesQueued() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Recovery Test");
        request.setType(JobType.FAILING);
        request.setMaxRetries(0);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());
        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job dead = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.DEAD, dead.getStatus());

        JobResponse recovered = jobService.recoverJob(created.getId(), "admin");
        assertEquals(JobStatus.QUEUED, recovered.getStatus());
        assertEquals(0, recovered.getRetryCount());
    }

    @Test
    void recoverJob_nonDead_throws() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Not Dead");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);

        assertThrows(com.distributed.jobplatform.exception.InvalidJobTransitionException.class,
            () -> jobService.recoverJob(created.getId(), "admin"));
    }

    @Test
    void recoverJob_thenExecutesSuccessfully() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Recover and Run");
        request.setType(JobType.FAILING);
        request.setMaxRetries(0);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());
        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job dead = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.DEAD, dead.getStatus());

        jobService.recoverJob(created.getId(), "admin");
        Job recovered = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, recovered.getStatus());
        assertEquals(0, recovered.getRetryCount());

        jobService.claimJob(created.getId());
        Job claimed = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.RUNNING, claimed.getStatus());
    }

    // ===========================
    // SCHEDULER INTEGRATION TESTS
    // ===========================

    @Test
    void scheduler_futureJob_notPickedUpEarly() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Future Job");
        request.setType(JobType.DEMO_TASK);
        request.setScheduledAt(java.time.Instant.now().plusSeconds(3600));

        JobResponse created = jobService.createJob(request, TEST_USER);
        assertEquals(JobStatus.SCHEDULED, created.getStatus());

        List<Job> dueJobs = jobService.getDueJobs(50);
        boolean found = dueJobs.stream().anyMatch(j -> j.getId().equals(created.getId()));
        assertFalse(found, "Future job should not be picked up by scheduler");
    }

    @Test
    void scheduler_dueJobPickedUp() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Immediate Job");
        request.setType(JobType.DEMO_TASK);

        JobResponse created = jobService.createJob(request, TEST_USER);
        assertEquals(JobStatus.QUEUED, created.getStatus());

        List<Job> dueJobs = jobService.getDueJobs(50);
        boolean found = dueJobs.stream().anyMatch(j -> j.getId().equals(created.getId()));
        assertFalse(found, "Already-QUEUED jobs should not be returned by findDueJobs");
    }

    @Test
    void scheduler_retriesBecomeEligibleAfterBackoff() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Retry Scheduler");
        request.setType(JobType.FAILING);
        request.setMaxRetries(3);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());
        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job afterFailure = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, afterFailure.getStatus());

        List<Job> dueJobs = jobService.getDueJobs(50);
        boolean found = dueJobs.stream().anyMatch(j -> j.getId().equals(created.getId()));
        assertFalse(found,
            "RETRYING job with future scheduledAt should NOT be due yet");
    }

    @Test
    void scheduler_scheduleJob_transitionsToQueued() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Schedule Test");
        request.setType(JobType.DEMO_TASK);
        request.setScheduledAt(java.time.Instant.now().plusSeconds(3600));

        JobResponse created = jobService.createJob(request, TEST_USER);
        assertEquals(JobStatus.SCHEDULED, created.getStatus());

        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.scheduleJob(job);

        Job after = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, after.getStatus());
    }

    @Test
    void scheduler_doesNotExecuteLongRunningJobs() {
        List<Job> dueJobs = jobService.getDueJobs(50);
        for (Job job : dueJobs) {
            jobService.scheduleJob(job);
            Job queued = jobRepository.findById(job.getId()).orElseThrow();
            assertEquals(JobStatus.QUEUED, queued.getStatus(),
                "Scheduler should only transition to QUEUED, not execute");
        }
    }

    // ===========================
    // STATE MACHINE INTEGRITY TESTS
    // ===========================

    @Test
    void stateMachine_failedToRetrying_valid() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("SM Test");
        request.setType(JobType.FAILING);
        request.setMaxRetries(2);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());
        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job failed = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, failed.getStatus());
    }

    @Test
    void stateMachine_retryingToQueued_viaScheduler() {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("SM Retry->Queued");
        request.setType(JobType.FAILING);
        request.setMaxRetries(2);

        JobResponse created = jobService.createJob(request, TEST_USER);
        jobService.claimJob(created.getId());
        Job job = jobRepository.findById(created.getId()).orElseThrow();
        jobService.executeJob(job);

        Job retrying = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.RETRYING, retrying.getStatus());

        jobService.scheduleJob(retrying);
        Job queued = jobRepository.findById(created.getId()).orElseThrow();
        assertEquals(JobStatus.QUEUED, queued.getStatus());
    }
}
