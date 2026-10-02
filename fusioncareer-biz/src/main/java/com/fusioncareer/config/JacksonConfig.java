package com.fusioncareer.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springdoc.core.utils.SpringDocUtils;

import jakarta.annotation.PostConstruct;

@Configuration
public class JacksonConfig {

    @PostConstruct
    public void documentLong() {
        SpringDocUtils.getConfig()
                .replaceWithClass(Long.class, String.class)
                .replaceWithClass(Long.TYPE, String.class);
    }

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer serializeLong() {
        return createBuilder -> createBuilder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
