package com.distributed.jobplatform.handler;

import com.distributed.jobplatform.enums.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Data cleanup handler.
 * Simulates cleaning up stale data based on retention policy.
 */
@Component
public class DataCleanupHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(DataCleanupHandler.class);

    @Override
    public String execute(String jobId, String payload) throws Exception {
        log.info("Executing DATA_CLEANUP job: {}", jobId);

        // Simulate cleanup work
        Thread.sleep(3000);

        int recordsDeleted = (int) (Math.random() * 1000);

        String result = String.format(
            "{\"status\": \"completed\", \"jobId\": \"%s\", \"recordsDeleted\": %d}",
            jobId, recordsDeleted);

        log.info("DATA_CLEANUP completed for job: {}, deleted {} records", jobId, recordsDeleted);
        return result;
    }

    @Override
    public JobType getSupportedType() {
        return JobType.DATA_CLEANUP;
    }
}
