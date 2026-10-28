package com.distributed.jobplatform.handler;

import com.distributed.jobplatform.enums.JobType;

/**
 * Interface for job execution handlers.
 * Each JobType maps to exactly one handler implementation.
 */
public interface JobHandler {

    /**
     * Execute the job with the given payload.
     * @param jobId the job ID
     * @param payload the job payload
     * @return the result payload as a string
     * @throws Exception if execution fails
     */
    String execute(String jobId, String payload) throws Exception;

    /**
     * Return the job type this handler supports.
     */
    JobType getSupportedType();
}
