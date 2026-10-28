package com.distributed.jobplatform.service;

import com.distributed.jobplatform.config.KafkaConfig;
import com.distributed.jobplatform.dto.JobEvent;
import com.distributed.jobplatform.enums.JobEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Publishes job lifecycle events to Kafka.
 * Delivery is best-effort; failure to publish does not prevent job state changes.
 * Uses at-least-once semantics.
 */
@Service
public class JobEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobEventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public JobEventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    public void publishEvent(JobEventType eventType, UUID jobId, String ownerId) {
        publishEvent(eventType, jobId, ownerId, null);
    }

    public void publishEvent(JobEventType eventType, UUID jobId, String ownerId, String metadata) {
        try {
            JobEvent event = new JobEvent(eventType, jobId, ownerId);
            event.setCorrelationId(UUID.randomUUID().toString());
            if (metadata != null) {
                event.setMetadata(metadata);
            }

            String json = objectMapper.writeValueAsString(event);

            CompletableFuture<SendResult<String, String>> future =
                kafkaTemplate.send(KafkaConfig.JOB_LIFECYCLE_TOPIC, jobId.toString(), json);

            future.whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Failed to publish Kafka event {} for job {}: {}",
                        eventType, jobId, ex.getMessage());
                } else {
                    log.debug("Published Kafka event {} for job {} to partition {}",
                        eventType, jobId, result.getRecordMetadata().partition());
                }
            });
        } catch (Exception e) {
            // Don't let Kafka failures affect job execution
            log.error("Error publishing Kafka event {} for job {}: {}",
                eventType, jobId, e.getMessage());
        }
    }
}
