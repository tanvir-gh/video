package com.tanvir.video.service;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OpenRouterProperties;
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
        var detectionProps = new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", workDir.toString());
        var openRouterProps = new OpenRouterProperties(
                "https://openrouter.ai/api/v1/chat/completions",
                "qwen/qwen3.5-flash-02-23", "", false);

        var triageService = new TriageService(detectionProps);
        var classifierService = new ClassifierService(detectionProps, openRouterProps);
        classifierService.loadPromptTemplate();
        var clipExtractorService = new ClipExtractorService(detectionProps);
        processingService = new StreamProcessingService(
                detectionProps, triageService, classifierService, clipExtractorService, hlsDir.toString());
    }

    @Test
    void createSession_returnsRunningSession() {
        StreamSession session = processingService.createSession("http://example.com/stream.m3u8");
        assertNotNull(session.getId());
        assertEquals(StreamSession.Status.RUNNING, session.getStatus());
        assertEquals("http://example.com/stream.m3u8", session.getStreamUrl());
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
