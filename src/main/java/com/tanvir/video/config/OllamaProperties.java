package com.tanvir.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ollama")
public record OllamaProperties(
    String url,
    String model,
    int maxTokens,
    double temperature
) {}
