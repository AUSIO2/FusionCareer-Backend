package com.fusioncareer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class FudanSsoConfig {

    @Bean
    public RestTemplate createRestClient() {
        return new RestTemplate();
    }
}
