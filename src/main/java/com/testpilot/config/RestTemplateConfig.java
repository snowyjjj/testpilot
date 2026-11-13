package com.testpilot.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
@EnableConfigurationProperties(LLMProperties.class)
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(LLMProperties properties) {
        return new RestTemplateBuilder()
                .setConnectTimeout(Duration.ofSeconds(properties.getConnectTimeout()))
                .setReadTimeout(Duration.ofSeconds(properties.getReadTimeout()))
                .build();
    }
}
