package com.tanvir.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.openrouter")
public record OpenRouterProperties(
    String apiUrl,
    String model,
    String apiKey,
    boolean enabled
) {}
