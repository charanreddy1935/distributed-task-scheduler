package com.distributed.jobplatform.handler;

import com.distributed.jobplatform.enums.JobType;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Registry that maps JobType to the appropriate JobHandler implementation.
 * Auto-discovers all JobHandler beans via Spring DI.
 */
@Component
public class JobHandlerRegistry {

    private final Map<JobType, JobHandler> handlers = new EnumMap<>(JobType.class);

    public JobHandlerRegistry(List<JobHandler> handlerList) {
        for (JobHandler handler : handlerList) {
            handlers.put(handler.getSupportedType(), handler);
        }
    }

    /**
     * Get the handler for a given job type.
     * @throws IllegalArgumentException if no handler registered for the type
     */
    public JobHandler getHandler(JobType jobType) {
        JobHandler handler = handlers.get(jobType);
        if (handler == null) {
            throw new IllegalArgumentException("No handler registered for job type: " + jobType);
        }
        return handler;
    }

    /**
     * Check if a handler is registered for the given job type.
     */
    public boolean hasHandler(JobType jobType) {
        return handlers.containsKey(jobType);
    }

    /**
     * Get all registered job types.
     */
    public Iterable<JobType> getRegisteredTypes() {
        return handlers.keySet();
    }
}
