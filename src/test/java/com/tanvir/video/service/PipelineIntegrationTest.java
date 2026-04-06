package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OllamaProperties;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
class PipelineIntegrationTest {

    @TempDir
    Path tempDir;

    private StreamProcessingService processingService;

    @BeforeEach
    void setUp() {
        Path workDir = tempDir.resolve("work");
        Path hlsDir = tempDir.resolve("hls");
        var detectionProps = new DetectionProperties(3, 0.7, 3, 512, false, "base", true, workDir.toString());
        var ollamaProps = new OllamaProperties("http://localhost:11434/api/chat", "qwen3.5:9b", 200, 0.1);

        var classifierService = new ClassifierService(detectionProps, ollamaProps);
        classifierService.loadPromptTemplate();
        var clipExtractorService = new ClipExtractorService(detectionProps);
        processingService = new StreamProcessingService(
                detectionProps, classifierService, clipExtractorService, hlsDir.toString());
    }

    @Test
    void createAndProcessSession() throws Exception {
        Path source = tempDir.resolve("tiny.ts");
        new ProcessBuilder("ffmpeg", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=160x120:d=3:rate=5",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=3",
                "-c:v", "libx264", "-preset", "ultrafast", "-c:a", "aac",
                source.toString())
                .redirectErrorStream(true).start().waitFor();

        var session = processingService.createSession(source.toString());
        List<String> events = new ArrayList<>();

        // processStream runs segmentation + sequential classification
        processingService.processStream(session.getId(), events::add);

        // Wait for async
        Thread.sleep(5000);

        assertFalse(events.isEmpty());
    }
}
