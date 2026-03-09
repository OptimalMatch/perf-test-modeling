package com.bank.wid.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    @Value("${wid.kafka.topic:wid-customer-alerts}")
    private String topicName;

    @Bean
    public NewTopic widCustomerAlertsTopic() {
        return TopicBuilder.name(topicName)
                .partitions(12)
                .replicas(1)
                .build();
    }
}
