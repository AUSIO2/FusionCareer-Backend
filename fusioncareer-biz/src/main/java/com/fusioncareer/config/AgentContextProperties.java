package com.fusioncareer.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "agent-context")
public class AgentContextProperties {
    private String secret = "";
    private long ttlSeconds = 300;
}
