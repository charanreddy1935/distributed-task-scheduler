package com.distributed.jobplatform;

import com.distributed.jobplatform.enums.JobPriority;
import com.distributed.jobplatform.service.JobService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class RetryLogicTest {

    // Test the backoff calculation directly (since it's a pure function concept)
    private long calculateBackoff(int retryCount) {
        long baseDelay = 1000L;
        long maxDelay = 30000L;
        long delay = baseDelay * (long) Math.pow(2, retryCount);
        return Math.min(delay, maxDelay);
    }

    @Test
    void backoff_first_retry_is_1_second() {
        assertEquals(1000L, calculateBackoff(0));
    }

    @Test
    void backoff_second_retry_is_2_seconds() {
        assertEquals(2000L, calculateBackoff(1));
    }

    @Test
    void backoff_third_retry_is_4_seconds() {
        assertEquals(4000L, calculateBackoff(2));
    }

    @Test
    void backoff_fourth_retry_is_8_seconds() {
        assertEquals(8000L, calculateBackoff(3));
    }

    @Test
    void backoff_fifth_retry_is_16_seconds() {
        assertEquals(16000L, calculateBackoff(4));
    }

    @Test
    void backoff_sixth_retry_is_capped_at_30_seconds() {
        assertEquals(30000L, calculateBackoff(5));
    }

    @Test
    void backoff_never_exceeds_max() {
        for (int i = 0; i < 20; i++) {
            assertTrue(calculateBackoff(i) <= 30000L);
        }
    }

    // ===========================
    // Priority Ordering Tests
    // ===========================

    @Test
    void priority_high_has_lowest_numeric_value() {
        assertTrue(JobPriority.HIGH.getNumericPriority() < JobPriority.MEDIUM.getNumericPriority());
        assertTrue(JobPriority.MEDIUM.getNumericPriority() < JobPriority.LOW.getNumericPriority());
    }

    @Test
    void priority_ordering_for_queue() {
        // Higher priority should sort first (lower numeric value)
        JobPriority[] priorities = {JobPriority.LOW, JobPriority.HIGH, JobPriority.MEDIUM};
        java.util.Arrays.sort(priorities, (a, b) -> Integer.compare(a.getNumericPriority(), b.getNumericPriority()));
        assertEquals(JobPriority.HIGH, priorities[0]);
        assertEquals(JobPriority.MEDIUM, priorities[1]);
        assertEquals(JobPriority.LOW, priorities[2]);
    }
}
