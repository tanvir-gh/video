package com.tanvir.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.detection")
public record DetectionProperties(
    double audioThresholdMultiplier,
    String ocrScoreboardRegion,
    double confidenceThreshold,
    int windowDuration,
    int windowOverlap,
    String whisperModel,
    String workDir
) {
    public int[] parseScoreboardRegion() {
        String[] parts = ocrScoreboardRegion.split(",");
        return new int[]{
            Integer.parseInt(parts[0].trim()),
            Integer.parseInt(parts[1].trim()),
            Integer.parseInt(parts[2].trim()),
            Integer.parseInt(parts[3].trim())
        };
    }
}
