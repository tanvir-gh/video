package com.tanvir.video.model;

public record TriageResult(
    int windowIndex,
    String windowPath,
    String audioPath,
    double audioRms,
    double baselineRms,
    String ocrBefore,
    String ocrAfter,
    boolean isCandidate,
    String reason
) {}
