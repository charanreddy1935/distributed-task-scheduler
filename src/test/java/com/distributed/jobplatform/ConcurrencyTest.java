package com.distributed.jobplatform;

import com.distributed.jobplatform.config.TestConfig;
import com.distributed.jobplatform.dto.CreateJobRequest;
import com.distributed.jobplatform.dto.JobResponse;
import com.distributed.jobplatform.enums.JobStatus;
import com.distributed.jobplatform.enums.JobType;
import com.distributed.jobplatform.repository.JobRepository;
import com.distributed.jobplatform.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestConfig.class)
@TestPropertySource(locations = "classpath:application.properties")
public class ConcurrencyTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
    }

    @Test
    void concurrentClaim_onlyOneWorkerWins() throws Exception {
        // Create a job
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Concurrency Test Job");
        request.setType(JobType.DEMO_TASK);

        JobResponse job = jobService.createJob(request, "test-user");
        assertEquals(JobStatus.QUEUED, job.getStatus());

        // Simulate 10 competing workers
        int workerCount = 10;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(workerCount);
        ExecutorService executor = Executors.newFixedThreadPool(workerCount);

        for (int i = 0; i < workerCount; i++) {
            final int workerId = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // All workers start simultaneously

                    boolean claimed = jobService.claimJob(job.getId());
                    if (claimed) {
                        successCount.incrementAndGet();
                        System.out.println("Worker " + workerId + " CLAIMED the job");
                    } else {
                        failureCount.incrementAndGet();
                        System.out.println("Worker " + workerId + " FAILED to claim (another worker won)");
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown(); // Signal all workers to start
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        // Verify exactly one worker won
        assertEquals(1, successCount.get(),
            "Exactly one worker should successfully claim the job");
        assertEquals(workerCount - 1, failureCount.get(),
            "All other workers should fail to claim");
    }

    @Test
    void concurrentClaim_multipleJobs_eachWinsExactlyOnce() throws Exception {
        // Create 5 jobs
        UUID[] jobIds = new UUID[5];
        for (int i = 0; i < 5; i++) {
            CreateJobRequest request = new CreateJobRequest();
            request.setName("Concurrent Job " + i);
            request.setType(JobType.DEMO_TASK);
            JobResponse job = jobService.createJob(request, "test-user");
            jobIds[i] = job.getId();
        }

        // Each job should be claimable exactly once
        for (UUID jobId : jobIds) {
            boolean first = jobService.claimJob(jobId);
            boolean second = jobService.claimJob(jobId);

            assertTrue(first, "First claim should succeed for job " + jobId);
            assertFalse(second, "Second claim should fail for job " + jobId);
        }
    }

    @Test
    void concurrentClaim_differentJobs_notInterfere() throws Exception {
        // Create 2 jobs
        CreateJobRequest request1 = new CreateJobRequest();
        request1.setName("Job A");
        request1.setType(JobType.DEMO_TASK);

        CreateJobRequest request2 = new CreateJobRequest();
        request2.setName("Job B");
        request2.setType(JobType.DEMO_TASK);

        JobResponse jobA = jobService.createJob(request1, "user-a");
        JobResponse jobB = jobService.createJob(request2, "user-b");

        // Workers claim different jobs simultaneously
        CountDownLatch latch = new CountDownLatch(2);
        AtomicInteger aClaimed = new AtomicInteger(0);
        AtomicInteger bClaimed = new AtomicInteger(0);

        Thread workerA = new Thread(() -> {
            try {
                if (jobService.claimJob(jobA.getId())) {
                    aClaimed.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        });

        Thread workerB = new Thread(() -> {
            try {
                if (jobService.claimJob(jobB.getId())) {
                    bClaimed.incrementAndGet();
                }
            } finally {
                latch.countDown();
            }
        });

        workerA.start();
        workerB.start();
        latch.await(10, TimeUnit.SECONDS);

        assertEquals(1, aClaimed.get(), "Job A should be claimed once");
        assertEquals(1, bClaimed.get(), "Job B should be claimed once");
    }

    @Test
    void claim_nonQueuedJob_fails() throws Exception {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Not Queued");
        request.setType(JobType.DEMO_TASK);
        request.setScheduledAt(java.time.Instant.now().plusSeconds(3600)); // Future scheduled

        JobResponse job = jobService.createJob(request, "test-user");
        assertEquals(JobStatus.SCHEDULED, job.getStatus());

        boolean claimed = jobService.claimJob(job.getId());
        assertFalse(claimed, "Should not be able to claim a non-queued job");
    }

    @Test
    void claim_alreadyRunningJob_fails() throws Exception {
        CreateJobRequest request = new CreateJobRequest();
        request.setName("Already Running");
        request.setType(JobType.DEMO_TASK);

        JobResponse job = jobService.createJob(request, "test-user");
        jobService.claimJob(job.getId()); // First claim succeeds
        boolean secondClaim = jobService.claimJob(job.getId()); // Second should fail

        assertFalse(secondClaim);
    }
}
