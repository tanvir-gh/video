package com.tanvir.video.service;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OllamaProperties;
import com.tanvir.video.model.ClassificationResult;

import static org.junit.jupiter.api.Assertions.*;

class ClassifierServiceTest {

    private ClassifierService classifier;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        var detectionProps = new DetectionProperties(30, 0.7, 3, 512, false, "base", true, tempDir.toString());
        var ollamaProps = new OllamaProperties("http://localhost:11434/api/chat", "qwen3.5:9b", 200, 0.1);
        classifier = new ClassifierService(detectionProps, ollamaProps);
        classifier.loadPromptTemplate();
    }

    @Test
    void buildPrompt_insertsTranscriptAndContext() {
        String prompt = classifier.buildPrompt("The crowd goes wild!", "Window 0: no events");
        assertTrue(prompt.contains("The crowd goes wild!"));
        assertTrue(prompt.contains("Window 0: no events"));
    }

    @Test
    void buildPrompt_handlesEmptyInputs() {
        String prompt = classifier.buildPrompt("", "");
        assertTrue(prompt.contains("No commentary transcript available."));
        assertTrue(prompt.contains("first window"));
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
    void classify_withEmptyFrames_stillWorks() {
        // With no ollama running in test, this will fail gracefully
        ClassificationResult result = classifier.classify(List.of(), "some transcript", "");
        // Either returns events from LLM or empty on connection failure
        assertNotNull(result);
    }
}
