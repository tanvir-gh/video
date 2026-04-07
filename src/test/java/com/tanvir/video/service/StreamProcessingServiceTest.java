package com.tanvir.video.service;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OllamaProperties;
import com.tanvir.video.model.StreamSession;

import static org.junit.jupiter.api.Assertions.*;

class StreamProcessingServiceTest {

    private StreamProcessingService processingService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        Path workDir = tempDir.resolve("work");
        Path hlsDir = tempDir.resolve("hls");
        var detectionProps = new DetectionProperties(30, 0.7, 3, 512, workDir.toString());
        var ollamaProps = new OllamaProperties("http://localhost:11434/api/chat", "qwen3.5:9b", 200, 0.1);

        var classifierService = new ClassifierService(detectionProps, ollamaProps);
        classifierService.loadPromptTemplate();
        var clipExtractorService = new ClipExtractorService(detectionProps);
        processingService = new StreamProcessingService(
                detectionProps, classifierService, clipExtractorService, hlsDir.toString());
    }

    @Test
    void createSession_returnsRunningSession() {
        StreamSession session = processingService.createSession("http://example.com/stream.m3u8");
        assertNotNull(session.getId());
        assertEquals(StreamSession.Status.RUNNING, session.getStatus());
    }

    @Test
    void getSession_returnsCreatedSession() {
        StreamSession session = processingService.createSession("http://example.com/stream.m3u8");
        assertSame(session, processingService.getSession(session.getId()));
    }

    @Test
    void getSession_returnsNullForUnknownId() {
        assertNull(processingService.getSession("nonexistent"));
    }

    @Test
    void stopSession_setsStatusToStopped() {
        StreamSession session = processingService.createSession("http://example.com/stream.m3u8");
        processingService.stopSession(session.getId());
        assertEquals(StreamSession.Status.STOPPED, session.getStatus());
    }
}
