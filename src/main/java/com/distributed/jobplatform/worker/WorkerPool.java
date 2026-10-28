package com.distributed.jobplatform.worker;

import com.distributed.jobplatform.entity.Job;
import com.distributed.jobplatform.service.JobService;
import io.micrometer.core.instrument.Gauge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class WorkerPool {

    private static final Logger log = LoggerFactory.getLogger(WorkerPool.class);

    private final ThreadPoolExecutor executor;
    private final JobService jobService;
    private final AtomicInteger activeWorkers;
    private final Set<UUID> submittedJobIds = ConcurrentHashMap.newKeySet();

    public WorkerPool(JobService jobService,
                     @Value("${app.worker.pool-size:5}") int poolSize,
                     @Value("${app.worker.queue-capacity:100}") int queueCapacity,
                     AtomicInteger activeWorkersGauge) {
        this.jobService = jobService;
        this.activeWorkers = activeWorkersGauge;

        this.executor = new ThreadPoolExecutor(
            poolSize,
            poolSize,
            60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(queueCapacity),
            new CustomThreadFactory(),
            new ThreadPoolExecutor.CallerRunsPolicy()
        );

        executor.allowCoreThreadTimeOut(true);

        log.info("Worker pool initialized: poolSize={}, queueCapacity={}", poolSize, queueCapacity);
    }

    public void submit(Job job) {
        if (submittedJobIds.contains(job.getId())) {
            log.debug("Job {} already submitted, skipping", job.getId());
            return;
        }

        submittedJobIds.add(job.getId());

        executor.submit(() -> {
            try {
                activeWorkers.incrementAndGet();
                executeWithClaim(job);
            } finally {
                activeWorkers.decrementAndGet();
                submittedJobIds.remove(job.getId());
            }
        });
    }

    private void executeWithClaim(Job job) {
        UUID jobId = job.getId();

        try {
            boolean claimed = jobService.claimJob(jobId);

            if (!claimed) {
                log.debug("Job {} was not claimed (another worker got it)", jobId);
                return;
            }

            jobService.executeJob(job);

        } catch (Exception e) {
            log.error("Unexpected error executing job {}: {}", jobId, e.getMessage());
        }
    }

    public int getActiveWorkerCount() {
        return activeWorkers.get();
    }

    public int getQueueSize() {
        return executor.getQueue().size();
    }

    public int getPoolSize() {
        return executor.getMaximumPoolSize();
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down worker pool...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("Worker pool did not terminate in 30 seconds, forcing shutdown");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("Worker pool shut down");
    }

    private static class CustomThreadFactory implements ThreadFactory {
        private int counter = 0;

        @Override
        public Thread newThread(Runnable r) {
            Thread thread = new Thread(r, "job-worker-" + (counter++));
            thread.setDaemon(false);
            thread.setPriority(Thread.NORM_PRIORITY);
            return thread;
        }
    }
}
