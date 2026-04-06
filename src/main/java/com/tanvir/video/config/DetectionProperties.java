package com.tanvir.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.detection")
public record DetectionProperties(
    int windowDuration,
    double confidenceThreshold,
    int keyframeCount,
    int keyframeWidth,
    boolean whisperEnabled,
    String whisperModel,
    boolean whisperTranslate,
    String workDir
) {}
