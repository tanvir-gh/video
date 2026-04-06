package com.tanvir.video.service;

import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OpenRouterProperties;
import com.tanvir.video.model.ClassificationResult;
import com.tanvir.video.model.TriageResult;

import static org.junit.jupiter.api.Assertions.*;

class ClassifierServiceTest {

    private ClassifierService classifier;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var detectionProps = new DetectionProperties(2.0, "0,0,300,50", 0.7, 30, 15, "base", tempDir.toString());
        var openRouterProps = new OpenRouterProperties(
                "https://openrouter.ai/api/v1/chat/completions",
                "qwen/qwen3.5-flash-02-23", "", false);
        classifier = new ClassifierService(detectionProps, openRouterProps);
        classifier.loadPromptTemplate();
    }

    @Test
    void buildPrompt_insertsAllPlaceholders() {
        var triage = new TriageResult(0, "video.ts", "audio.wav",
                0.15, 0.05, "1 - 0", "2 - 0", true, "audio spike");

        String prompt = classifier.buildPrompt("The crowd goes wild!", triage);

        assertTrue(prompt.contains("The crowd goes wild!"));
        assertTrue(prompt.contains("1 - 0"));
        assertTrue(prompt.contains("2 - 0"));
        assertTrue(prompt.contains("0.15"));
        assertTrue(prompt.contains("0.05"));
    }

    @Test
    void parseResponse_validJson() {
        String json = """
                {"events": [{"type": "goal", "confidence": 0.92, "description": "Score changes to 2-0"}]}
                """;
        ClassificationResult result = classifier.parseResponse(json);
        assertEquals(1, result.events().size());
        assertEquals("goal", result.events().get(0).type());
        assertEquals(0.92, result.events().get(0).confidence(), 0.01);
    }

    @Test
    void parseResponse_emptyEvents() {
        ClassificationResult result = classifier.parseResponse("{\"events\": []}");
        assertTrue(result.events().isEmpty());
    }

    @Test
    void parseResponse_malformedJsonReturnsEmpty() {
        ClassificationResult result = classifier.parseResponse("not json at all");
        assertTrue(result.events().isEmpty());
    }

    @Test
    void parseResponse_stripsMarkdownFences() {
        String json = """
                ```json
                {"events": [{"type": "penalty", "confidence": 0.88, "description": "Penalty awarded"}]}
                ```
                """;
        ClassificationResult result = classifier.parseResponse(json);
        assertEquals(1, result.events().size());
        assertEquals("penalty", result.events().get(0).type());
    }

    @Test
    void classify_whenDisabled_returnsEmptyResult() throws Exception {
        var triage = new TriageResult(0, "video.ts", "audio.wav",
                0.15, 0.05, "1 - 0", "2 - 0", true, "audio spike");

        ClassificationResult result = classifier.classify(triage, "Goal scored!");
        assertTrue(result.events().isEmpty());
    }
}
