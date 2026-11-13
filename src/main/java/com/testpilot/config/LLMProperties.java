package com.testpilot.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties(prefix = "testpilot.llm")
public class LLMProperties {

    private String baseUrl;

    private String model;

    private String apiKey;

    /** connect-timeout in seconds */
    private int connectTimeout = 10;

    /** read-timeout in seconds */
    private int readTimeout = 60;

    public boolean hasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }
}
