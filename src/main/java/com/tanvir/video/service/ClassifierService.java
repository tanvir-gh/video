package com.tanvir.video.service;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OpenRouterProperties;
import com.tanvir.video.model.ClassificationResult;
import com.tanvir.video.model.TriageResult;

import jakarta.annotation.PostConstruct;

@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);

    private final DetectionProperties detectionProps;
    private final OpenRouterProperties openRouterProps;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String promptTemplate;

    public ClassifierService(DetectionProperties detectionProps, OpenRouterProperties openRouterProps) {
        this.detectionProps = detectionProps;
        this.openRouterProps = openRouterProps;
    }

    @PostConstruct
    void loadPromptTemplate() {
        try {
            var resource = new ClassPathResource("prompts/classify_event.txt");
            promptTemplate = new String(resource.getInputStream().readAllBytes());
        } catch (Exception e) {
            log.error("Failed to load prompt template", e);
            promptTemplate = "";
        }
    }

    public String transcribeAudio(Path audioPath) throws Exception {
        Path outputDir = audioPath.getParent();
        ProcessBuilder pb = new ProcessBuilder(
                "whisper", audioPath.toString(),
                "--model", detectionProps.whisperModel(),
                "--language", "en",
                "--output_format", "json",
                "--output_dir", outputDir.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.waitFor();

        String baseName = audioPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path jsonPath = outputDir.resolve(baseName + ".json");

        if (!jsonPath.toFile().exists()) {
            log.warn("Whisper produced no output for {}", audioPath);
            return "";
        }

        JsonNode root = objectMapper.readTree(jsonPath.toFile());
        if (root.has("text")) {
            return root.get("text").asText();
        }
        return "";
    }

    public String buildPrompt(String transcript, TriageResult triage) {
        return promptTemplate
                .replace("{transcript}", transcript)
                .replace("{ocr_before}", triage.ocrBefore())
                .replace("{ocr_after}", triage.ocrAfter())
                .replace("{audio_rms}", String.format("%.4f", triage.audioRms()))
                .replace("{baseline_rms}", String.format("%.4f", triage.baselineRms()));
    }

    public ClassificationResult classify(TriageResult triage, String transcript) throws Exception {
        if (!openRouterProps.enabled()) {
            log.info("LLM disabled — skipping classification for window {}", triage.windowIndex());
            return new ClassificationResult(List.of());
        }

        String prompt = buildPrompt(transcript, triage);
        return callLlm(prompt);
    }

    ClassificationResult callLlm(String prompt) {
        try {
            WebClient client = WebClient.create(openRouterProps.apiUrl());
            String requestBody = objectMapper.writeValueAsString(new java.util.LinkedHashMap<>() {{
                put("model", openRouterProps.model());
                put("messages", List.of(
                        new java.util.LinkedHashMap<>() {{
                            put("role", "user");
                            put("content", prompt);
                        }}
                ));
                put("reasoning", new java.util.LinkedHashMap<>() {{
                    put("effort", "none");
                }});
            }});

            String response = client.post()
                    .header("Authorization", "Bearer " + openRouterProps.apiKey())
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/choices/0/message/content").asText();
            return parseResponse(content);
        } catch (Exception e) {
            log.error("LLM call failed: {}", e.getMessage());
            return new ClassificationResult(List.of());
        }
    }

    public ClassificationResult parseResponse(String json) {
        try {
            String cleaned = json.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode eventsNode = root.get("events");
            if (eventsNode == null || !eventsNode.isArray()) {
                return new ClassificationResult(List.of());
            }
            List<ClassificationResult.Event> events = new java.util.ArrayList<>();
            for (JsonNode e : eventsNode) {
                events.add(new ClassificationResult.Event(
                        e.get("type").asText(),
                        e.get("confidence").asDouble(),
                        e.has("description") ? e.get("description").asText() : ""
                ));
            }
            return new ClassificationResult(events);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return new ClassificationResult(List.of());
        }
    }
}
