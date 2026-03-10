package com.bank.moo.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@Configuration
@Profile("oracle")
@EnableJpaRepositories(basePackages = "com.bank.moo.repository")
@EnableTransactionManagement
public class OracleConfig {
}
