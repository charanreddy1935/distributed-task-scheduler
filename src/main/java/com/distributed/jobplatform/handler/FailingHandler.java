package com.distributed.jobplatform.handler;

import com.distributed.jobplatform.enums.JobType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FailingHandler implements JobHandler {

    private static final Logger log = LoggerFactory.getLogger(FailingHandler.class);

    @Override
    public String execute(String jobId, String payload) throws Exception {
        log.info("Executing FAILING job: {} (always throws)", jobId);
        throw new RuntimeException("Intentional handler failure for testing retry and dead paths");
    }

    @Override
    public JobType getSupportedType() {
        return JobType.FAILING;
    }
}
