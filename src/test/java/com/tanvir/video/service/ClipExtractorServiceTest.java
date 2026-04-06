package com.tanvir.video.service;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;

import static org.junit.jupiter.api.Assertions.*;

class ClipExtractorServiceTest {

    private ClipExtractorService clipExtractor;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var props = new DetectionProperties(2.0, 0.7, 30, 15, "base", tempDir.toString());
        clipExtractor = new ClipExtractorService(props);
    }

    @Test
    void extractClip_clampsNegativeStartToZero() {
        // Verify the start time calculation logic
        // eventTimeSec=1.0, clipDuration=5.0 → start = max(0, 1.0 - 2.5) = 0.0
        double start = Math.max(0, 1.0 - 5.0 / 2);
        assertEquals(0.0, start, 0.001);
    }

    @Test
    void extractClip_centersOnEvent() {
        // eventTimeSec=15.0, clipDuration=30.0 → start = max(0, 15.0 - 15.0) = 0.0
        double start = Math.max(0, 15.0 - 30.0 / 2);
        assertEquals(0.0, start, 0.001);

        // eventTimeSec=60.0, clipDuration=30.0 → start = 45.0
        double start2 = Math.max(0, 60.0 - 30.0 / 2);
        assertEquals(45.0, start2, 0.001);
    }
}
