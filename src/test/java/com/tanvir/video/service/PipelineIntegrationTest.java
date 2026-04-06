package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OpenRouterProperties;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test using ffmpeg. Run with: ./gradlew test -PincludeTags=integration
 * Uses a tiny 3-second synthetic video — completes in ~5 seconds.
 */
@Tag("integration")
class PipelineIntegrationTest {

    @TempDir
    Path tempDir;

    private StreamProcessingService processingService;
    private TriageService triageService;

    @BeforeEach
    void setUp() {
        Path workDir = tempDir.resolve("work");
        Path hlsDir = tempDir.resolve("hls");
        // Use 3-second windows so the tiny test video produces exactly 1 window
        var detectionProps = new DetectionProperties(2.0, "0,0,300,50", 0.7, 3, 1, "base", workDir.toString());
        var openRouterProps = new OpenRouterProperties(
                "https://openrouter.ai/api/v1/chat/completions",
                "qwen/qwen3.5-flash-02-23", "", false);

        triageService = new TriageService(detectionProps);
        var classifierService = new ClassifierService(detectionProps, openRouterProps);
        classifierService.loadPromptTemplate();
        var clipExtractorService = new ClipExtractorService(detectionProps);
        processingService = new StreamProcessingService(
                detectionProps, triageService, classifierService, clipExtractorService, hlsDir.toString());
    }

    @Test
    void segmentAndTriage_tinyVideo() throws Exception {
        // Generate a 3-second video at minimal resolution — fast to create
        Path source = tempDir.resolve("tiny.ts");
        new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=160x120:d=3:rate=5",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                source.toString())
                .redirectErrorStream(true).start().waitFor();

        Path workDir = tempDir.resolve("work");
        Files.createDirectories(workDir);

        var windows = processingService.segmentStream(source, workDir);
        assertFalse(windows.isEmpty(), "Should produce at least one window");

        // Triage the first window
        double rms = triageService.extractAudioRms(windows.get(0).audioPath());
        assertTrue(rms > 0, "Should detect audio in test video");

        var result = triageService.triage(0, windows.get(0).videoPath(),
                windows.get(0).audioPath(), rms * 0.5, workDir);
        // With baseline at half the actual RMS, this should trigger as candidate
        assertTrue(result.isCandidate(), "Should flag as candidate when baseline is low");
    }
}
