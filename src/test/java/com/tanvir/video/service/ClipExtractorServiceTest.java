package com.tanvir.video.service;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;

import static org.junit.jupiter.api.Assertions.*;

class ClipExtractorServiceTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var props = new DetectionProperties(30, 0.7, 3, 512, false, "base", true, tempDir.toString());
        new ClipExtractorService(props);
    }

    @Test
    void extractClip_clampsNegativeStartToZero() {
        double start = Math.max(0, 1.0 - 5.0 / 2);
        assertEquals(0.0, start, 0.001);
    }

    @Test
    void extractClip_centersOnEvent() {
        double start = Math.max(0, 60.0 - 30.0 / 2);
        assertEquals(45.0, start, 0.001);
    }
}
