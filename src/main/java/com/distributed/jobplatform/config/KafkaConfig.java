package com.distributed.jobplatform.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String JOB_LIFECYCLE_TOPIC = "job-lifecycle-events";

    @Bean
    public NewTopic jobLifecycleTopic() {
        return TopicBuilder.name(JOB_LIFECYCLE_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }
}
