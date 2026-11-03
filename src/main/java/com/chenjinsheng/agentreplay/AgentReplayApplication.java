package com.chenjinsheng.agentreplay;

import com.chenjinsheng.agentreplay.config.AgentProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class AgentReplayApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentReplayApplication.class, args);
    }
}
