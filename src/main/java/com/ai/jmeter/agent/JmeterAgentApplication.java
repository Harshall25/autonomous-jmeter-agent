package com.ai.jmeter.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Boots the Autonomous JMeter Performance Testing Agent. */
@SpringBootApplication
public class JmeterAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(JmeterAgentApplication.class, args);
    }
}
