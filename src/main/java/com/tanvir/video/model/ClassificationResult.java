package com.tanvir.video.model;

import java.util.List;

public record ClassificationResult(
    List<Event> events
) {
    public record Event(
        String type,
        double confidence,
        String description
    ) {}
}
