package com.distributed.jobplatform;

import com.distributed.jobplatform.enums.JobType;
import com.distributed.jobplatform.handler.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class HandlerRegistryTest {

    private JobHandlerRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new JobHandlerRegistry(List.of(
            new DemoTaskHandler(),
            new ReportHandler(),
            new DataCleanupHandler(),
            new EmailHandler(),
            new FailingHandler()
        ));
    }

    @Test
    void hasHandler_for_all_types() {
        assertTrue(registry.hasHandler(JobType.DEMO_TASK));
        assertTrue(registry.hasHandler(JobType.REPORT));
        assertTrue(registry.hasHandler(JobType.DATA_CLEANUP));
        assertTrue(registry.hasHandler(JobType.EMAIL));
        assertTrue(registry.hasHandler(JobType.FAILING));
    }

    @Test
    void getHandler_returns_correct_handler() {
        assertEquals(JobType.DEMO_TASK, registry.getHandler(JobType.DEMO_TASK).getSupportedType());
        assertEquals(JobType.REPORT, registry.getHandler(JobType.REPORT).getSupportedType());
        assertEquals(JobType.DATA_CLEANUP, registry.getHandler(JobType.DATA_CLEANUP).getSupportedType());
        assertEquals(JobType.EMAIL, registry.getHandler(JobType.EMAIL).getSupportedType());
        assertEquals(JobType.FAILING, registry.getHandler(JobType.FAILING).getSupportedType());
    }

    @Test
    void getRegisteredTypes_contains_all() {
        var types = registry.getRegisteredTypes();
        assertTrue(types.iterator().hasNext());
        int count = 0;
        for (JobType type : types) {
            count++;
        }
        assertEquals(5, count);
    }
}
