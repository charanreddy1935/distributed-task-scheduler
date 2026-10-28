package com.distributed.jobplatform.scheduler;

import com.distributed.jobplatform.entity.Job;
import com.distributed.jobplatform.enums.JobStatus;
import com.distributed.jobplatform.service.JobService;
import com.distributed.jobplatform.worker.WorkerPool;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Scheduler that periodically inspects due jobs and admits them to the queue.
 *
 * Responsibilities:
 *   - Find CREATED jobs (immediate)
 *   - Find SCHEDULED jobs whose scheduledAt time has passed
 *   - Transition them to QUEUED
 *   - Submit queued jobs to the worker pool
 *
 * The scheduler does NOT directly execute jobs.
 * Scheduling accuracy is approximately ±scheduler.interval_seconds.
 *
 * Uses bounded batch queries to prevent overload.
 */
@Component
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JobService jobService;
    private final WorkerPool workerPool;
    private final int batchSize;
    private final AtomicInteger queueDepth;

    public JobScheduler(JobService jobService,
                       WorkerPool workerPool,
                       @Value("${app.scheduler.batch-size:50}") int batchSize,
                       AtomicInteger queueDepthGauge) {
        this.jobService = jobService;
        this.workerPool = workerPool;
        this.batchSize = batchSize;
        this.queueDepth = queueDepthGauge;
    }

    /**
     * Main scheduling loop. Runs at a fixed interval.
     * Finds due jobs and submits them to the worker pool.
     */
    @Scheduled(fixedDelayString = "${app.scheduler.interval-seconds:5}", initialDelay = 5000)
    public void scheduleDueJobs() {
        try {
            // Step 1: Find due jobs (CREATED, SCHEDULED with past scheduledAt, RETRYING with past backoff)
            List<Job> dueJobs = jobService.getDueJobs(batchSize);

            log.debug("Found {} due jobs to schedule", dueJobs.size());

            int scheduled = 0;
            for (Job job : dueJobs) {
                try {
                    // Step 2: Transition to QUEUED
                    jobService.scheduleJob(job);

                    // Step 3: Submit to worker pool
                    workerPool.submit(job);

                    scheduled++;
                } catch (Exception e) {
                    log.error("Failed to schedule job {}: {}", job.getId(), e.getMessage());
                }
            }

            // Also check for QUEUED jobs that need to be dispatched
            List<Job> queuedJobs = jobService.getQueuedJobsForDispatch(batchSize);
            for (Job job : queuedJobs) {
                try {
                    workerPool.submit(job);
                } catch (Exception e) {
                    log.debug("Job {} may already be submitted: {}", job.getId(), e.getMessage());
                }
            }

            queueDepth.set(queuedJobs.size());
            log.debug("Scheduled {} jobs, dispatched {} queued jobs", scheduled, queuedJobs.size());

        } catch (Exception e) {
            log.error("Error in scheduling loop: {}", e.getMessage());
        }
    }
}
