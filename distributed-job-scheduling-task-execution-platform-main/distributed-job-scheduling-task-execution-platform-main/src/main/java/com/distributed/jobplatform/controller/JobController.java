package com.distributed.jobplatform.controller;

import com.distributed.jobplatform.dto.*;
import com.distributed.jobplatform.enums.JobStatus;
import com.distributed.jobplatform.exception.RateLimitException;
import com.distributed.jobplatform.service.JobService;
import com.distributed.jobplatform.service.RateLimitService;
import io.micrometer.core.annotation.Timed;
import io.micrometer.core.instrument.Counter;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final RateLimitService rateLimitService;
    private final Counter rateLimitRejectionCounter;

    public JobController(JobService jobService,
                         RateLimitService rateLimitService,
                         Counter rateLimitRejectionCounter) {
        this.jobService = jobService;
        this.rateLimitService = rateLimitService;
        this.rateLimitRejectionCounter = rateLimitRejectionCounter;
    }

    /**
     * Rate limit check for job-creating requests. The injected counter is the
     * same `rate_limit.rejections` Counter bean from MetricsConfig (previously
     * dead code — it was registered but never incremented).
     */
    private RateLimitService.RateLimitResult checkRateLimit(Authentication authentication) {
        String userId = authentication.getName();
        RateLimitService.RateLimitResult result = rateLimitService.checkRateLimit(userId);
        if (!result.isAllowed()) {
            rateLimitRejectionCounter.increment();
            throw new RateLimitException("Rate limit exceeded. Maximum " +
                rateLimitService.getMaxRequestsPerMinute() + " requests per minute.");
        }
        return result;
    }

    private ResponseEntity.BodyBuilder applyRateLimitHeaders(RateLimitService.RateLimitResult result, ResponseEntity.BodyBuilder builder) {
        builder.header("X-RateLimit-Limit", String.valueOf(result.getLimit()));
        builder.header("X-RateLimit-Remaining", String.valueOf(result.getRemaining()));
        if (result.getRetryAfterSeconds() > 0) {
            builder.header("Retry-After", String.valueOf(result.getRetryAfterSeconds()));
        }
        return builder;
    }

    /**
     * POST /api/jobs - Create a new job
     */
    @PostMapping
    @Timed(value = "api.jobs.create", description = "Time to create a job")
    public ResponseEntity<JobResponse> createJob(
            @Valid @RequestBody CreateJobRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            Authentication authentication) {

        RateLimitService.RateLimitResult rl = checkRateLimit(authentication);

        // Set idempotency key from header if provided
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            request.setIdempotencyKey(idempotencyKey);
        }

        JobResponse response = jobService.createJob(request, authentication.getName());
        return applyRateLimitHeaders(rl, ResponseEntity.status(HttpStatus.CREATED)).body(response);
    }

    /**
     * GET /api/jobs - List jobs for the authenticated user
     */
    @GetMapping
    @Timed(value = "api.jobs.list", description = "Time to list jobs")
    public ResponseEntity<Page<JobResponse>> getJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) JobStatus status,
            Authentication authentication) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<JobResponse> jobs;
        if (status != null) {
            jobs = jobService.getJobsByOwnerAndStatus(authentication.getName(), status, pageRequest);
        } else {
            jobs = jobService.getJobsByOwner(authentication.getName(), pageRequest);
        }

        return ResponseEntity.ok(jobs);
    }

    /**
     * GET /api/jobs/{id} - Get job details
     */
    @GetMapping("/{id}")
    @Timed(value = "api.jobs.get", description = "Time to get a job")
    public ResponseEntity<JobResponse> getJob(@PathVariable UUID id, Authentication authentication) {
        JobResponse response = jobService.getJobById(id, authentication.getName());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/jobs/{id}/cancel - Cancel a job
     */
    @PostMapping("/{id}/cancel")
    @Timed(value = "api.jobs.cancel", description = "Time to cancel a job")
    public ResponseEntity<JobResponse> cancelJob(@PathVariable UUID id, Authentication authentication) {
        JobResponse response = jobService.cancelJob(id, authentication.getName());
        return ResponseEntity.ok(response);
    }

    /**
     * POST /api/jobs/{id}/retry - Manually retry a job
     */
    @PostMapping("/{id}/retry")
    @Timed(value = "api.jobs.retry", description = "Time to retry a job")
    public ResponseEntity<JobResponse> retryJob(@PathVariable UUID id, Authentication authentication) {
        JobResponse response = jobService.retryJob(id, authentication.getName());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/jobs/{id}/attempts - Get job attempts (ownership enforced)
     */
    @GetMapping("/{id}/attempts")
    @Timed(value = "api.jobs.attempts", description = "Time to get job attempts")
    public ResponseEntity<List<JobAttemptResponse>> getJobAttempts(@PathVariable UUID id, Authentication authentication) {
        List<JobAttemptResponse> attempts = jobService.getJobAttempts(id, authentication.getName());
        return ResponseEntity.ok(attempts);
    }

    /**
     * GET /api/jobs/{id}/result - Get job result (ownership enforced)
     */
    @GetMapping("/{id}/result")
    @Timed(value = "api.jobs.result", description = "Time to get job result")
    public ResponseEntity<JobResultResponse> getJobResult(@PathVariable UUID id, Authentication authentication) {
        JobResultResponse result = jobService.getJobResult(id, authentication.getName());
        if (result == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/jobs/{id}/recover - Admin recovery of DEAD jobs (ADMIN role enforced by SecurityConfig)
     */
    @PostMapping("/{id}/recover")
    @Timed(value = "api.jobs.recover", description = "Time to recover a job")
    public ResponseEntity<JobResponse> recoverJob(@PathVariable UUID id, Authentication authentication) {
        JobResponse response = jobService.recoverJob(id, authentication.getName());
        return ResponseEntity.ok(response);
    }

    /**
     * GET /api/admin/jobs - Admin: list all jobs
     */
    @GetMapping("/admin/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Page<JobResponse>> getAllJobs(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) JobStatus status) {

        PageRequest pageRequest = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));

        Page<JobResponse> jobs;
        if (status != null) {
            jobs = jobService.getJobsByStatus(status, pageRequest);
        } else {
            jobs = jobService.getAllJobs(pageRequest);
        }

        return ResponseEntity.ok(jobs);
    }
}
