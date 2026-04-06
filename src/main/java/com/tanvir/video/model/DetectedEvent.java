package com.tanvir.video.model;

public record DetectedEvent(
    String type,
    double confidence,
    String description,
    int windowIndex,
    double timestampSec,
    String clipPath,
    String transcript
) {}
