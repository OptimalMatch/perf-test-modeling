package com.bank.moo.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${moo.kafka.topic:moo-customer-alerts}")
    private String topicName;

    @Bean
    public NewTopic mooCustomerAlertsTopic() {
        return TopicBuilder.name(topicName)
                .partitions(12)
                .replicas(1)
                .build();
    }
}
