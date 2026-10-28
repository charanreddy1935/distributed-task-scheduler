package com.distributed.jobplatform.handler;

import com.distributed.jobplatform.enums.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Report generation handler.
 * Simulates generating a report from a payload specification.
 */
@Component
public class ReportHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(ReportHandler.class);

    @Override
    public String execute(String jobId, String payload) throws Exception {
        log.info("Executing REPORT job: {}, payload: {}", jobId, payload);

        // Simulate report generation work
        Thread.sleep(5000);

        String result = String.format(
            "{\"status\": \"completed\", \"jobId\": \"%s\", \"reportType\": \"generated\", \"pages\": %d}",
            jobId, (int) (Math.random() * 100) + 1);

        log.info("REPORT completed for job: {}", jobId);
        return result;
    }

    @Override
    public JobType getSupportedType() {
        return JobType.REPORT;
    }
}
