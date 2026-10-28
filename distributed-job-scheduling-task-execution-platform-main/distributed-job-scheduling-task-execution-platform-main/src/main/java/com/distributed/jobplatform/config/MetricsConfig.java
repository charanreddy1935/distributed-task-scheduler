package com.distributed.jobplatform.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class MetricsConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }

    private final AtomicInteger activeWorkers = new AtomicInteger(0);
    private final AtomicInteger queueDepth = new AtomicInteger(0);

    @Bean
    public Counter jobCreatedCounter(MeterRegistry registry) {
        return Counter.builder("jobs.created")
            .description("Total jobs created")
            .register(registry);
    }

    @Bean
    public Counter jobCompletedCounter(MeterRegistry registry) {
        return Counter.builder("jobs.completed")
            .description("Total jobs completed successfully")
            .register(registry);
    }

    @Bean
    public Counter jobFailedCounter(MeterRegistry registry) {
        return Counter.builder("jobs.failed")
            .description("Total jobs failed")
            .register(registry);
    }

    @Bean
    public Counter jobRetriedCounter(MeterRegistry registry) {
        return Counter.builder("jobs.retried")
            .description("Total jobs retried")
            .register(registry);
    }

    @Bean
    public Counter jobDeadCounter(MeterRegistry registry) {
        return Counter.builder("jobs.dead")
            .description("Total jobs moved to DEAD state")
            .register(registry);
    }

    @Bean
    public Counter jobCancelledCounter(MeterRegistry registry) {
        return Counter.builder("jobs.cancelled")
            .description("Total jobs cancelled")
            .register(registry);
    }

    @Bean
    public Counter rateLimitRejectionCounter(MeterRegistry registry) {
        return Counter.builder("rate_limit.rejections")
            .description("Total rate limit rejections")
            .register(registry);
    }

    @Bean
    public Timer jobExecutionTimer(MeterRegistry registry) {
        return Timer.builder("jobs.execution.duration")
            .description("Job execution duration")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(registry);
    }

    @Bean
    public AtomicInteger activeWorkersGauge(MeterRegistry registry) {
        Gauge.builder("workers.active", activeWorkers, AtomicInteger::doubleValue)
            .description("Number of active workers")
            .register(registry);
        return activeWorkers;
    }

    @Bean
    public AtomicInteger queueDepthGauge(MeterRegistry registry) {
        Gauge.builder("jobs.queue.depth", queueDepth, AtomicInteger::doubleValue)
            .description("Current queue depth")
            .register(registry);
        return queueDepth;
    }
}
