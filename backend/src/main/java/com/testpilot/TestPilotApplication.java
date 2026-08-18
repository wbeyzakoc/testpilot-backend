package com.testpilot;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class TestPilotApplication {
    public static void main(String[] args) {
        SpringApplication.run(TestPilotApplication.class, args);
    }
}