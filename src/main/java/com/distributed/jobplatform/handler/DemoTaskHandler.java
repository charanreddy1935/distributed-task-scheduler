package com.distributed.jobplatform.handler;

import com.distributed.jobplatform.enums.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Demo task handler for testing the job execution pipeline.
 * Simulates a task that takes some time to complete.
 */
@Component
public class DemoTaskHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DemoTaskHandler.class);

    @Override
    public String execute(String jobId, String payload) throws Exception {
        log.info("Executing DEMO_TASK for job: {}", jobId);

        // Simulate work
        Thread.sleep(2000);

        String result = String.format(
            "{\"status\": \"completed\", \"jobId\": \"%s\", \"message\": \"Demo task executed successfully\", \"payload\": %s}",
            jobId, payload != null ? payload : "null");

        log.info("DEMO_TASK completed for job: {}", jobId);
        return result;
    }

    @Override
    public JobType getSupportedType() {
        return JobType.DEMO_TASK;
    }
}
