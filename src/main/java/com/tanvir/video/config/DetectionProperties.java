package com.tanvir.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.detection")
public record DetectionProperties(
    double audioThresholdMultiplier,
    double confidenceThreshold,
    int windowDuration,
    int windowOverlap,
    String whisperModel,
    String workDir
) {}
