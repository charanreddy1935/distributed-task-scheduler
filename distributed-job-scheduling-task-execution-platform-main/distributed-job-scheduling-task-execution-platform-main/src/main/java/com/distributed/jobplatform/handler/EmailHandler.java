package com.distributed.jobplatform.handler;

import com.distributed.jobplatform.enums.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Email handler.
 * Simulates sending an email based on payload specification.
 * In production, this would integrate with an actual email service.
 */
@Component
public class EmailHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(EmailHandler.class);

    @Override
    public String execute(String jobId, String payload) throws Exception {
        log.info("Executing EMAIL job: {}", jobId);

        // Simulate email sending
        Thread.sleep(1000);

        String result = String.format(
            "{\"status\": \"completed\", \"jobId\": \"%s\", \"sent\": true}",
            jobId);

        log.info("EMAIL completed for job: {}", jobId);
        return result;
    }

    @Override
    public JobType getSupportedType() {
        return JobType.EMAIL;
    }
}
