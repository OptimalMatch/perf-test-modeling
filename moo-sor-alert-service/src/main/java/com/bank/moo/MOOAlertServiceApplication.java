package com.bank.moo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class MOOAlertServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(MOOAlertServiceApplication.class, args);
    }
}
