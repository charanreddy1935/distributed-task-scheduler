package com.distributed.jobplatform.service;

import com.distributed.jobplatform.dto.*;
import com.distributed.jobplatform.entity.*;
import com.distributed.jobplatform.enums.*;
import com.distributed.jobplatform.exception.*;
import com.distributed.jobplatform.handler.JobHandler;
import com.distributed.jobplatform.handler.JobHandlerRegistry;
import com.distributed.jobplatform.job.JobStateMachine;
import com.distributed.jobplatform.repository.*;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    private final JobRepository jobRepository;
    private final JobAttemptRepository attemptRepository;
    private final JobResultRepository resultRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final JobStateMachine stateMachine;
    private final JobHandlerRegistry handlerRegistry;
    private final JobEventPublisher eventPublisher;
    private final Counter jobCreatedCounter;
    private final Counter jobCompletedCounter;
    private final Counter jobFailedCounter;
    private final Counter jobRetriedCounter;
    private final Counter jobDeadCounter;
    private final Counter jobCancelledCounter;
    private final Timer jobExecutionTimer;

    public JobService(JobRepository jobRepository,
                     JobAttemptRepository attemptRepository,
                     JobResultRepository resultRepository,
                     IdempotencyRecordRepository idempotencyRepository,
                     JobStateMachine stateMachine,
                     JobHandlerRegistry handlerRegistry,
                     JobEventPublisher eventPublisher,
                     Counter jobCreatedCounter,
                     Counter jobCompletedCounter,
                     Counter jobFailedCounter,
                     Counter jobRetriedCounter,
                     Counter jobDeadCounter,
                     Counter jobCancelledCounter,
                     Timer jobExecutionTimer) {
        this.jobRepository = jobRepository;
        this.attemptRepository = attemptRepository;
        this.resultRepository = resultRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.stateMachine = stateMachine;
        this.handlerRegistry = handlerRegistry;
        this.eventPublisher = eventPublisher;
        this.jobCreatedCounter = jobCreatedCounter;
        this.jobCompletedCounter = jobCompletedCounter;
        this.jobFailedCounter = jobFailedCounter;
        this.jobRetriedCounter = jobRetriedCounter;
        this.jobDeadCounter = jobDeadCounter;
        this.jobCancelledCounter = jobCancelledCounter;
        this.jobExecutionTimer = jobExecutionTimer;
    }

    // ===========================
    // JOB CREATION
    // ===========================

    /**
     * Create a job. Intentionally NOT @Transactional so that each repository call
     * runs in its own transaction. This allows concurrent idempotency-key races to be
     * resolved by catching DataIntegrityViolationException and falling back to a
     * fresh lookup in a new transaction.
     */
    public JobResponse createJob(CreateJobRequest request, String ownerId) {
        if (!handlerRegistry.hasHandler(request.getType())) {
            throw new IllegalArgumentException("No handler registered for job type: " + request.getType());
        }

        if (request.getIdempotencyKey() != null && !request.getIdempotencyKey().isBlank()) {
            return handleIdempotency(request, ownerId);
        }

        return doCreateJob(request, ownerId);
    }

    private JobResponse doCreateJob(CreateJobRequest request, String ownerId) {
        Job job = new Job(ownerId, request.getName(), request.getType(), request.getPriority(), request.getPayload());
        job.setMaxRetries(request.getMaxRetries());
        job.setTimeoutSeconds(request.getTimeoutSeconds());
        job.setIdempotencyKey(request.getIdempotencyKey());

        if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(Instant.now())) {
            job.setScheduledAt(request.getScheduledAt());
            stateMachine.validateTransition(JobStatus.CREATED, JobStatus.SCHEDULED);
            job.setStatus(JobStatus.SCHEDULED);
        } else {
            stateMachine.validateTransition(JobStatus.CREATED, JobStatus.QUEUED);
            job.setStatus(JobStatus.QUEUED);
            if (request.getScheduledAt() != null) {
                job.setScheduledAt(request.getScheduledAt());
            }
        }

        job = jobRepository.save(job);

        eventPublisher.publishEvent(JobEventType.JOB_CREATED, job.getId(), ownerId);
        if (job.getStatus() == JobStatus.QUEUED) {
            eventPublisher.publishEvent(JobEventType.JOB_QUEUED, job.getId(), ownerId);
        }

        jobCreatedCounter.increment();
        log.info("Job created: {} (type={}, priority={}, status={})", job.getId(), job.getType(), job.getPriority(), job.getStatus());

        return toResponse(job);
    }

    /**
     * Handle idempotency-key creation. Each repository call is its own transaction,
     * so a DataIntegrityViolationException from one save does not doom subsequent lookups.
     * On concurrent duplicate: the unique constraint on jobs.idempotencyKey rejects the second
     * insert, and we look up and return the existing job.
     */
    private JobResponse handleIdempotency(CreateJobRequest request, String ownerId) {
        String key = request.getIdempotencyKey();

        IdempotencyRecord existing = idempotencyRepository.findByIdempotencyKey(key).orElse(null);
        if (existing != null) {
            // SECURITY: idempotency keys are scoped to their owner. A key recorded for a
            // different user must not reveal that user's job (existence oracle / data leak).
            // The caller gets a neutral 409 Conflict, with no indication the key exists.
            if (!existing.getOwnerId().equals(ownerId)) {
                log.warn("Idempotency key {} belongs to a different owner; rejecting with 409", key);
                throw new DuplicateJobException("Idempotency key conflict");
            }
            log.info("Duplicate idempotency key detected: {}, returning existing job: {}", key, existing.getJobId());
            Job existingJob = jobRepository.findById(existing.getJobId())
                .orElseThrow(() -> new JobNotFoundException(existing.getJobId()));
            return toResponse(existingJob);
        }

        try {
            Job job = new Job(ownerId, request.getName(), request.getType(), request.getPriority(), request.getPayload());
            job.setMaxRetries(request.getMaxRetries());
            job.setTimeoutSeconds(request.getTimeoutSeconds());
            job.setIdempotencyKey(key);

            if (request.getScheduledAt() != null && request.getScheduledAt().isAfter(Instant.now())) {
                job.setScheduledAt(request.getScheduledAt());
                job.setStatus(JobStatus.SCHEDULED);
            } else {
                job.setStatus(JobStatus.QUEUED);
                if (request.getScheduledAt() != null) {
                    job.setScheduledAt(request.getScheduledAt());
                }
            }

            job = jobRepository.save(job);

            IdempotencyRecord record = new IdempotencyRecord(key, job.getId(), ownerId);
            idempotencyRepository.save(record);

            eventPublisher.publishEvent(JobEventType.JOB_CREATED, job.getId(), ownerId);
            if (job.getStatus() == JobStatus.QUEUED) {
                eventPublisher.publishEvent(JobEventType.JOB_QUEUED, job.getId(), ownerId);
            }

            jobCreatedCounter.increment();
            return toResponse(job);

        } catch (DataIntegrityViolationException e) {
            log.info("Concurrent idempotency race for key {}, resolving via lookup", key);
            Job existingJob = jobRepository.findByIdempotencyKey(key).orElse(null);
            if (existingJob != null) {
                // SECURITY: same ownership rule as the pre-insert lookup — a concurrent
                // insert by a different owner resolves to a neutral 409, not their job.
                if (!existingJob.getOwnerId().equals(ownerId)) {
                    log.warn("Idempotency key {} concurrently claimed by a different owner; rejecting with 409", key);
                    throw new DuplicateJobException("Idempotency key conflict");
                }
                return toResponse(existingJob);
            }
            throw new RuntimeException("Concurrent idempotency conflict: key=" + key, e);
        }
    }

    // ===========================
    // JOB QUERIES
    // ===========================

    @Transactional(readOnly = true)
    public Page<JobResponse> getJobsByOwner(String ownerId, Pageable pageable) {
        return jobRepository.findByOwnerId(ownerId, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> getJobsByOwnerAndStatus(String ownerId, JobStatus status, Pageable pageable) {
        return jobRepository.findByOwnerIdAndStatus(ownerId, status, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> getAllJobs(Pageable pageable) {
        return jobRepository.findAll(pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public Page<JobResponse> getJobsByStatus(JobStatus status, Pageable pageable) {
        return jobRepository.findByStatus(status, pageable).map(this::toResponse);
    }

    @Transactional(readOnly = true)
    public JobResponse getJobById(UUID jobId, String ownerId) {
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));
        if (!job.getOwnerId().equals(ownerId)) {
            throw new JobNotFoundException("Job not found or access denied");
        }
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public JobResponse getJobByIdAdmin(UUID jobId) {
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));
        return toResponse(job);
    }

    // ===========================
    // JOB CANCELLATION
    // ===========================

    @Transactional
    public JobResponse cancelJob(UUID jobId, String ownerId) {
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));

        if (!job.getOwnerId().equals(ownerId)) {
            throw new JobNotFoundException("Job not found or access denied");
        }

        stateMachine.validateTransition(job.getStatus(), JobStatus.CANCELLED);
        job.setStatus(JobStatus.CANCELLED);
        job.setCompletedAt(Instant.now());
        job = jobRepository.save(job);

        eventPublisher.publishEvent(JobEventType.JOB_CANCELLED, job.getId(), ownerId);
        jobCancelledCounter.increment();

        log.info("Job cancelled: {}", jobId);
        return toResponse(job);
    }

    // ===========================
    // JOB RETRY (manual)
    // ===========================

    @Transactional
    public JobResponse retryJob(UUID jobId, String ownerId) {
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));

        if (!job.getOwnerId().equals(ownerId)) {
            throw new JobNotFoundException("Job not found or access denied");
        }

        if (job.getStatus() != JobStatus.FAILED && job.getStatus() != JobStatus.DEAD) {
            throw new InvalidJobTransitionException("Can only retry FAILED or DEAD jobs");
        }

        if (job.getStatus() == JobStatus.DEAD) {
            // Manual retry of a DEAD job is a controlled recovery, identical in kind to
            // the admin /recover endpoint (state machine has no direct DEAD -> QUEUED edge).
            stateMachine.validateRecovery(job.getStatus());
        } else {
            // FAILED jobs follow the normal retry path FAILED -> RETRYING -> QUEUED.
            stateMachine.validateTransition(job.getStatus(), JobStatus.RETRYING);
            stateMachine.validateTransition(JobStatus.RETRYING, JobStatus.QUEUED);
        }

        job.setStatus(JobStatus.QUEUED);
        job.setRetryCount(0);
        job.setCompletedAt(null);
        job = jobRepository.save(job);

        eventPublisher.publishEvent(JobEventType.JOB_QUEUED, job.getId(), ownerId);

        log.info("Job manually retried: {}", jobId);
        return toResponse(job);
    }

    // ===========================
    // ADMIN RECOVERY
    // ===========================

    @Transactional
    public JobResponse recoverJob(UUID jobId, String adminUsername) {
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));

        stateMachine.validateRecovery(job.getStatus());

        job.setStatus(JobStatus.QUEUED);
        job.setRetryCount(0);
        job.setCompletedAt(null);
        job = jobRepository.save(job);

        eventPublisher.publishEvent(JobEventType.JOB_QUEUED, job.getId(), job.getOwnerId());
        log.info("DEAD job recovered by admin: {} (admin={})", jobId, adminUsername);
        return toResponse(job);
    }

    // ===========================
    // JOB ATTEMPTS & RESULTS
    // ===========================

    /**
     * SECURITY: attempts derive ownership from the associated Job.ownerId.
     * Access to attempts of a job the caller does not own returns 404, not a data leak.
     */
    @Transactional(readOnly = true)
    public List<JobAttemptResponse> getJobAttempts(UUID jobId, String ownerId) {
        requireJobOwnership(jobId, ownerId);
        return attemptRepository.findByJobIdOrderByAttemptNumberDesc(jobId).stream()
            .map(this::toAttemptResponse)
            .toList();
    }

    /**
     * SECURITY: results derive ownership from the associated Job.ownerId.
     */
    @Transactional(readOnly = true)
    public JobResultResponse getJobResult(UUID jobId, String ownerId) {
        requireJobOwnership(jobId, ownerId);
        List<JobResult> results = resultRepository.findAllByJobIdOrderByCreatedAtDesc(jobId);
        if (results.isEmpty()) {
            return null;
        }
        return toResultResponse(results.get(0));
    }

    /**
     * Ownership guard: throws JobNotFoundException (404, no resource existence leak)
     * when the job does not exist or does not belong to the caller.
     */
    private void requireJobOwnership(UUID jobId, String ownerId) {
        Job job = jobRepository.findById(jobId)
            .orElseThrow(() -> new JobNotFoundException(jobId));
        if (!job.getOwnerId().equals(ownerId)) {
            throw new JobNotFoundException("Job not found or access denied");
        }
    }

    // ===========================
    // SCHEDULING (called by Scheduler)
    // ===========================

    @Transactional
    public List<Job> getDueJobs(int batchSize) {
        return jobRepository.findDueJobs(Instant.now(),
            org.springframework.data.domain.PageRequest.of(0, batchSize));
    }

    @Transactional
    public void scheduleJob(Job job) {
        stateMachine.validateTransition(job.getStatus(), JobStatus.QUEUED);
        job.setStatus(JobStatus.QUEUED);
        jobRepository.save(job);
        eventPublisher.publishEvent(JobEventType.JOB_QUEUED, job.getId(), job.getOwnerId());
        log.info("Job scheduled to queue: {}", job.getId());
    }

    // ===========================
    // WORKER DISPATCH (called by Worker)
    // ===========================

    @Transactional
    public List<Job> getQueuedJobsForDispatch(int batchSize) {
        return jobRepository.findQueuedJobsForDispatch(
            org.springframework.data.domain.PageRequest.of(0, batchSize));
    }

    /**
     * Claim a job QUEUED -> RUNNING via an atomic conditional UPDATE.
     * The transaction lives on the repository method itself.
     */
    public boolean claimJob(UUID jobId) {
        int affected = jobRepository.claimJob(jobId, Instant.now());
        if (affected == 1) {
            eventPublisher.publishEvent(JobEventType.JOB_STARTED, jobId, "system");
            log.debug("Job claimed successfully: {}", jobId);
            return true;
        }
        log.debug("Job claim failed (already claimed by another worker): {}", jobId);
        return false;
    }

    // ===========================
    // JOB EXECUTION (with timeout + retry)
    // ===========================

    /**
     * Atomic SUCCESS transition (only if still RUNNING); see JobRepository for the
     * transaction boundary. Returns affected-row count so callers detect races.
     */
    public int markJobSuccessIfRunning(UUID jobId) {
        return jobRepository.markJobSuccessIfRunning(jobId, Instant.now());
    }

    /**
     * Atomic FAILED transition (only if still RUNNING); see JobRepository for the
     * transaction boundary. Returns affected-row count so callers detect races.
     */
    public int markJobFailedIfRunning(UUID jobId) {
        return jobRepository.markJobFailedIfRunning(jobId, Instant.now());
    }

    /**
     * Execute a claimed job with timeout enforcement.
     * The handler runs in a separate thread via CompletableFuture.
     * On timeout the executing thread is interrupted (best-effort / cooperative).
     * On failure the job is automatically retried with exponential backoff or moved to DEAD.
     *
     * TRANSACTION BOUNDARY: intentionally NOT @Transactional. The handler may run for
     * up to timeoutSeconds (user-configurable up to 3600s); holding a DB connection for
     * that long would exhaust the connection pool under concurrency. Every persistence
     * step below is its own short transaction (repository-level @Transactional /
     * SimpleJpaRepository.save), and the RUNNING -> SUCCESS/FAILED decision is an atomic
     * conditional UPDATE that is always the last write, so correctness does not depend
     * on one enclosing transaction.
     */
    public void executeJob(Job job) {
        UUID jobId = job.getId();
        int attemptNumber = job.getRetryCount() + 1;

        JobAttempt attempt = createAttempt(jobId, attemptNumber);

        try {
            JobHandler handler = handlerRegistry.getHandler(job.getType());

            CompletableFuture<String> future = CompletableFuture.supplyAsync(
                () -> {
                    try {
                        return handler.execute(jobId.toString(), job.getPayload());
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            );

            String result;
            try {
                result = future.get(job.getTimeoutSeconds(), TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                handleTimeout(job, attempt);
                return;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw cause instanceof Exception ? (Exception) cause : new RuntimeException(cause);
            }

            completeJob(job, attempt, result);

        } catch (Exception e) {
            failJobAndRetry(job, attempt, e);
        }
    }

    // Private method: Spring @Transactional would be inert here (self-invocation).
    // attemptRepository.save() opens its own short transaction (SimpleJpaRepository).
    private JobAttempt createAttempt(UUID jobId, int attemptNumber) {
        JobAttempt attempt = new JobAttempt(jobId, attemptNumber);
        return attemptRepository.save(attempt);
    }

    /**
     * Persist successful completion. Uses atomic update so only one winner in a
     * timeout-vs-completion race.
     */
    private void completeJob(Job job, JobAttempt attempt, String resultPayload) {
        attempt.setStatus(AttemptStatus.COMPLETED);
        attempt.setCompletedAt(Instant.now());
        attemptRepository.save(attempt);

        JobResult result = new JobResult(job.getId(), attempt.getId(), true, resultPayload);
        resultRepository.save(result);

        int affected = markJobSuccessIfRunning(job.getId());
        if (affected == 1) {
            eventPublisher.publishEvent(JobEventType.JOB_SUCCEEDED, job.getId(), job.getOwnerId());
            jobCompletedCounter.increment();

            Duration duration = Duration.between(attempt.getStartedAt(), attempt.getCompletedAt());
            jobExecutionTimer.record(duration);

            log.info("Job completed successfully: {} (attempt {})", job.getId(), attempt.getAttemptNumber());
        } else {
            log.debug("Job completion race detected (job already transitioned): {}", job.getId());
        }
    }

    /**
     * Persist failure and trigger automatic retry logic.
     * Uses atomic update so only one winner in a timeout-vs-failure race.
     */
    private void failJobAndRetry(Job job, JobAttempt attempt, Exception e) {
        String errorMsg = e.getMessage() != null
            ? e.getMessage().substring(0, Math.min(1000, e.getMessage().length()))
            : "Unknown error";

        attempt.setStatus(AttemptStatus.FAILED);
        attempt.setCompletedAt(Instant.now());
        attempt.setErrorMessage(errorMsg);
        attempt.setErrorClass(e.getClass().getSimpleName());
        attemptRepository.save(attempt);

        JobResult result = new JobResult(job.getId(), attempt.getId(), false, null);
        result.setErrorMessage(errorMsg);
        resultRepository.save(result);

        int affected = markJobFailedIfRunning(job.getId());
        if (affected == 1) {
            eventPublisher.publishEvent(JobEventType.JOB_FAILED, job.getId(), job.getOwnerId(),
                e.getClass().getSimpleName());
            jobFailedCounter.increment();

            Duration duration = Duration.between(attempt.getStartedAt(), attempt.getCompletedAt());
            jobExecutionTimer.record(duration);

            log.warn("Job failed: {} (attempt {}): {}", job.getId(), attempt.getAttemptNumber(), e.getMessage());

            jobRepository.findById(job.getId()).ifPresent(this::handleRetryableFailure);
        } else {
            log.debug("Job failure race detected (job already transitioned): {}", job.getId());
        }
    }

    /**
     * Persist timeout as TIMED_OUT attempt and trigger automatic retry logic.
     * Uses atomic update so only one winner in a timeout-vs-completion race.
     */
    private void handleTimeout(Job job, JobAttempt attempt) {
        String errorMsg = "Job execution timed out after " + job.getTimeoutSeconds() + " seconds";

        attempt.setStatus(AttemptStatus.TIMED_OUT);
        attempt.setCompletedAt(Instant.now());
        attempt.setErrorMessage(errorMsg);
        attempt.setErrorClass("TimeoutException");
        attemptRepository.save(attempt);

        JobResult result = new JobResult(job.getId(), attempt.getId(), false, null);
        result.setErrorMessage(errorMsg);
        resultRepository.save(result);

        int affected = markJobFailedIfRunning(job.getId());
        if (affected == 1) {
            eventPublisher.publishEvent(JobEventType.JOB_FAILED, job.getId(), job.getOwnerId(), "TimeoutException");
            jobFailedCounter.increment();

            Duration duration = Duration.between(attempt.getStartedAt(), attempt.getCompletedAt());
            jobExecutionTimer.record(duration);

            log.warn("Job timed out: {} (attempt {})", job.getId(), attempt.getAttemptNumber());

            jobRepository.findById(job.getId()).ifPresent(this::handleRetryableFailure);
        } else {
            log.debug("Job timeout race detected (job already transitioned): {}", job.getId());
        }
    }

    // ===========================
    // AUTOMATIC RETRY SYSTEM
    // ===========================

    /**
     * After a failure (exception or timeout), decide whether to retry or go DEAD.
     * The job must be in FAILED status at this point (set by atomic update).
     *
     * Retry strategy: set status to RETRYING with scheduledAt = now + exponential backoff.
     * The scheduler picks up RETRYING jobs whose scheduledAt has passed and transitions
     * them to QUEUED. This avoids blocking worker threads during backoff.
     *
     * Flow: FAILED -> RETRYING (with scheduledAt) -> [scheduler picks up] -> QUEUED -> RUNNING
     * Or:   FAILED -> DEAD (when retries exhausted)
     */
    private void handleRetryableFailure(Job job) {
        if (job.getStatus() != JobStatus.FAILED) {
            log.debug("Job {} is not in FAILED state ({}), skipping retry logic", job.getId(), job.getStatus());
            return;
        }

        if (job.getRetryCount() >= job.getMaxRetries()) {
            stateMachine.validateTransition(job.getStatus(), JobStatus.DEAD);
            job.setStatus(JobStatus.DEAD);
            job.setCompletedAt(Instant.now());
            jobRepository.save(job);
            eventPublisher.publishEvent(JobEventType.JOB_DEAD, job.getId(), job.getOwnerId());
            jobDeadCounter.increment();
            log.warn("Job moved to DEAD state (retries exhausted): {}", job.getId());
        } else {
            long backoffMs = calculateBackoff(job.getRetryCount());
            Instant retryAt = Instant.now().plusMillis(backoffMs);

            stateMachine.validateTransition(job.getStatus(), JobStatus.RETRYING);
            job.setStatus(JobStatus.RETRYING);
            job.setRetryCount(job.getRetryCount() + 1);
            job.setScheduledAt(retryAt);
            job.setCompletedAt(null);
            jobRepository.save(job);

            eventPublisher.publishEvent(JobEventType.JOB_RETRYING, job.getId(), job.getOwnerId());
            jobRetriedCounter.increment();

            log.info("Job scheduled for retry: {} (attempt {}, backoff {}ms, scheduled at {})",
                job.getId(), job.getRetryCount(), backoffMs, retryAt);
        }
    }

    /**
     * Calculate exponential backoff delay in milliseconds.
     * Formula: min(baseDelay * 2^retryCount, maxDelay)
     * baseDelay = 1s, maxDelay = 30s, no jitter.
     */
    public long calculateBackoff(int retryCount) {
        long baseDelay = 1000L;
        long maxDelay = 30000L;
        long delay = baseDelay * (long) Math.pow(2, retryCount);
        return Math.min(delay, maxDelay);
    }

    // ===========================
    // DTO MAPPING
    // ===========================

    private JobResponse toResponse(Job job) {
        JobResponse response = new JobResponse();
        response.setId(job.getId());
        response.setOwnerId(job.getOwnerId());
        response.setName(job.getName());
        response.setType(job.getType());
        response.setPriority(job.getPriority());
        response.setStatus(job.getStatus());
        response.setPayload(job.getPayload());
        response.setScheduledAt(job.getScheduledAt());
        response.setStartedAt(job.getStartedAt());
        response.setCompletedAt(job.getCompletedAt());
        response.setRetryCount(job.getRetryCount());
        response.setMaxRetries(job.getMaxRetries());
        response.setTimeoutSeconds(job.getTimeoutSeconds());
        response.setIdempotencyKey(job.getIdempotencyKey());
        response.setCreatedAt(job.getCreatedAt());
        response.setUpdatedAt(job.getUpdatedAt());
        return response;
    }

    private JobAttemptResponse toAttemptResponse(JobAttempt attempt) {
        JobAttemptResponse response = new JobAttemptResponse();
        response.setId(attempt.getId());
        response.setJobId(attempt.getJobId());
        response.setAttemptNumber(attempt.getAttemptNumber());
        response.setStatus(attempt.getStatus());
        response.setStartedAt(attempt.getStartedAt());
        response.setCompletedAt(attempt.getCompletedAt());
        response.setErrorMessage(attempt.getErrorMessage());
        response.setErrorClass(attempt.getErrorClass());
        return response;
    }

    private JobResultResponse toResultResponse(JobResult result) {
        JobResultResponse response = new JobResultResponse();
        response.setId(result.getId());
        response.setJobId(result.getJobId());
        response.setAttemptId(result.getAttemptId());
        response.setResultPayload(result.getResultPayload());
        response.setSuccess(result.isSuccess());
        response.setErrorMessage(result.getErrorMessage());
        response.setCreatedAt(result.getCreatedAt());
        return response;
    }
}
