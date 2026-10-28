package com.distributed.jobplatform;

import com.distributed.jobplatform.config.TestConfig;
import com.distributed.jobplatform.dto.CreateJobRequest;
import com.distributed.jobplatform.dto.JobResponse;
import com.distributed.jobplatform.enums.JobType;
import com.distributed.jobplatform.exception.DuplicateJobException;
import com.distributed.jobplatform.repository.JobRepository;
import com.distributed.jobplatform.service.JobService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Import(TestConfig.class)
@TestPropertySource(locations = "classpath:application.properties")
public class IdempotencyConcurrencyTest {

    @Autowired
    private JobService jobService;

    @Autowired
    private JobRepository jobRepository;

    @BeforeEach
    void setUp() {
        jobRepository.deleteAll();
    }

    @Test
    void concurrentSameKey_exactlyOneJobCreated() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        int threadCount = 20;
        AtomicInteger successCount = new AtomicInteger(0);
        ConcurrentHashMap<UUID, Integer> jobIds = new ConcurrentHashMap<>();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    CreateJobRequest request = new CreateJobRequest();
                    request.setName("Concurrent Job " + idx);
                    request.setType(JobType.DEMO_TASK);
                    request.setIdempotencyKey(idempotencyKey);

                    JobResponse response = jobService.createJob(request, "test-user");
                    successCount.incrementAndGet();
                    jobIds.merge(response.getId(), 1, Integer::sum);
                } catch (Exception e) {
                    System.out.println("Thread " + idx + " failed: " + e.getClass().getSimpleName());
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(30, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(successCount.get() > 0, "At least one thread should succeed");
        assertEquals(1, jobIds.size(), "Exactly one unique job ID should be created, but found: " + jobIds);
        System.out.println("Concurrent idempotency: " + successCount.get() + " threads succeeded, " +
            jobIds.size() + " unique job(s) created");
    }

    @Test
    void sequentialSameKey_returnsSameJob() {
        String idempotencyKey = UUID.randomUUID().toString();

        CreateJobRequest request = new CreateJobRequest();
        request.setName("First Request");
        request.setType(JobType.DEMO_TASK);
        request.setIdempotencyKey(idempotencyKey);

        JobResponse first = jobService.createJob(request, "test-user");

        CreateJobRequest request2 = new CreateJobRequest();
        request2.setName("Second Request");
        request2.setType(JobType.DEMO_TASK);
        request2.setIdempotencyKey(idempotencyKey);

        JobResponse second = jobService.createJob(request2, "test-user");

        assertEquals(first.getId(), second.getId(), "Same idempotency key must return same job");
        assertEquals(first.getName(), second.getName(), "First request's data should be preserved");
    }

    @Test
    void sameKey_differentOwner_rejected_notOtherUsersJob() {
        String idempotencyKey = UUID.randomUUID().toString();

        CreateJobRequest request = new CreateJobRequest();
        request.setName("Owner A Job");
        request.setType(JobType.DEMO_TASK);
        request.setIdempotencyKey(idempotencyKey);
        JobResponse first = jobService.createJob(request, "owner-a");

        // SECURITY: a different user reusing the key must NOT receive owner A's job
        // (no data leak, no existence oracle). Neutral conflict instead.
        CreateJobRequest request2 = new CreateJobRequest();
        request2.setName("Owner B Attempt");
        request2.setType(JobType.DEMO_TASK);
        request2.setIdempotencyKey(idempotencyKey);
        assertThrows(DuplicateJobException.class, () -> jobService.createJob(request2, "owner-b"));

        // The legitimate owner still gets the same logical job back
        CreateJobRequest request3 = new CreateJobRequest();
        request3.setName("Owner A Retry");
        request3.setType(JobType.DEMO_TASK);
        request3.setIdempotencyKey(idempotencyKey);
        JobResponse again = jobService.createJob(request3, "owner-a");
        assertEquals(first.getId(), again.getId());
    }

    @Test
    void differentKeys_createDifferentJobs() {
        CreateJobRequest req1 = new CreateJobRequest();
        req1.setName("Job A");
        req1.setType(JobType.DEMO_TASK);
        req1.setIdempotencyKey(UUID.randomUUID().toString());

        CreateJobRequest req2 = new CreateJobRequest();
        req2.setName("Job B");
        req2.setType(JobType.DEMO_TASK);
        req2.setIdempotencyKey(UUID.randomUUID().toString());

        JobResponse first = jobService.createJob(req1, "test-user");
        JobResponse second = jobService.createJob(req2, "test-user");

        assertNotEquals(first.getId(), second.getId());
        assertEquals("Job A", first.getName());
        assertEquals("Job B", second.getName());
    }

    @Test
    void repeatedRequest_afterFirstCompletes_returnsExistingJob() {
        String idempotencyKey = UUID.randomUUID().toString();

        CreateJobRequest request = new CreateJobRequest();
        request.setName("Original Job");
        request.setType(JobType.DEMO_TASK);
        request.setIdempotencyKey(idempotencyKey);

        JobResponse first = jobService.createJob(request, "test-user");
        assertNotNull(first.getId());

        CreateJobRequest duplicate = new CreateJobRequest();
        duplicate.setName("Duplicate Attempt");
        duplicate.setType(JobType.REPORT);
        duplicate.setIdempotencyKey(idempotencyKey);

        JobResponse second = jobService.createJob(duplicate, "test-user");

        assertEquals(first.getId(), second.getId());
        assertEquals("Original Job", second.getName(), "Original data preserved");
    }

    @Test
    void concurrentFirstRequests_highContention() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        int threadCount = 50;
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        Set<UUID> createdJobIds = ConcurrentHashMap.newKeySet();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(threadCount);
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(threadCount, 20));

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    CreateJobRequest request = new CreateJobRequest();
                    request.setName("High Contention Job " + idx);
                    request.setType(JobType.DEMO_TASK);
                    request.setIdempotencyKey(idempotencyKey);

                    JobResponse response = jobService.createJob(request, "test-user");
                    successCount.incrementAndGet();
                    createdJobIds.add(response.getId());
                } catch (Exception e) {
                    failureCount.incrementAndGet();
                } finally {
                    endLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        endLatch.await(60, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(1, createdJobIds.size(),
            "Exactly one unique job should be created across " + threadCount + " concurrent requests. " +
            "Successes: " + successCount.get() + ", Failures: " + failureCount.get() +
            ", Unique IDs: " + createdJobIds);
    }
}
