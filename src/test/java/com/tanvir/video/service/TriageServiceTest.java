package com.tanvir.video.service;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;

import static org.junit.jupiter.api.Assertions.*;

class TriageServiceTest {

    private TriageService triageService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var props = new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", tempDir.toString());
        triageService = new TriageService(props);
    }

    @Test
    void isAudioSpike_detectsSpikeAboveBaseline() {
        assertTrue(triageService.isAudioSpike(0.15, 0.05));
    }

    @Test
    void isAudioSpike_rejectsNormalLevel() {
        assertFalse(triageService.isAudioSpike(0.06, 0.05));
    }

    @Test
    void isAudioSpike_handlesZeroBaseline() {
        assertTrue(triageService.isAudioSpike(0.01, 0.0));
        assertFalse(triageService.isAudioSpike(0.0, 0.0));
    }

    @Test
    void hasScoreboardChanged_detectsDifference() {
        assertTrue(triageService.hasScoreboardChanged("1 - 0", "2 - 0"));
    }

    @Test
    void hasScoreboardChanged_noChangeWhenSame() {
        assertFalse(triageService.hasScoreboardChanged("1 - 0", "1 - 0"));
    }

    @Test
    void hasScoreboardChanged_handlesEmptyStrings() {
        assertFalse(triageService.hasScoreboardChanged("", ""));
    }

    @Test
    void hasScoreboardChanged_handlesNull() {
        assertFalse(triageService.hasScoreboardChanged(null, "1 - 0"));
        assertFalse(triageService.hasScoreboardChanged("1 - 0", null));
    }
}
