package com.bank.moo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.mongodb.config.EnableMongoAuditing;

@Configuration
@Profile("!oracle")
@EnableMongoAuditing
public class MongoConfig {
}
