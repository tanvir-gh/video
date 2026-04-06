package com.tanvir.video.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OllamaProperties;
import com.tanvir.video.model.ClassificationResult;

import jakarta.annotation.PostConstruct;

@Service
public class ClassifierService {

    private static final Logger log = LoggerFactory.getLogger(ClassifierService.class);

    private final DetectionProperties detectionProps;
    private final OllamaProperties ollamaProps;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private String promptTemplate;

    public ClassifierService(DetectionProperties detectionProps, OllamaProperties ollamaProps) {
        this.detectionProps = detectionProps;
        this.ollamaProps = ollamaProps;
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

    /**
     * Extract evenly-spaced keyframes from a video window, resized to configured width.
     */
    public List<Path> extractKeyframes(Path videoPath, Path workDir) throws Exception {
        int count = detectionProps.keyframeCount();
        int duration = detectionProps.windowDuration();
        int width = detectionProps.keyframeWidth();
        List<Path> frames = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            // Evenly spaced, avoiding first/last 2 seconds
            double ts = 2.0 + (duration - 4.0) * i / (count - 1);
            Path framePath = workDir.resolve(String.format("kf_%03d_%.0f.png", i, ts));

            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y",
                    "-i", videoPath.toString(),
                    "-ss", String.valueOf(ts),
                    "-frames:v", "1",
                    "-vf", "scale=" + width + ":-1",
                    framePath.toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.getInputStream().readAllBytes();
            p.waitFor();

            if (Files.exists(framePath) && Files.size(framePath) > 0) {
                frames.add(framePath);
            }
        }
        log.debug("Extracted {} keyframes from {}", frames.size(), videoPath.getFileName());
        return frames;
    }

    /**
     * Encode frame files as base64 strings for the ollama vision API.
     */
    public List<String> encodeFrames(List<Path> framePaths) throws IOException {
        List<String> encoded = new ArrayList<>();
        for (Path p : framePaths) {
            encoded.add(Base64.getEncoder().encodeToString(Files.readAllBytes(p)));
        }
        return encoded;
    }

    /**
     * Optionally transcribe audio using Whisper, with auto-detected language.
     */
    public String transcribeAudio(Path audioPath) throws Exception {
        if (!detectionProps.whisperEnabled()) {
            return "";
        }

        log.info("Whisper: transcribing {} (model={})", audioPath.getFileName(), detectionProps.whisperModel());
        long start = System.currentTimeMillis();

        List<String> cmd = new ArrayList<>(List.of(
                "whisper", audioPath.toString(),
                "--model", detectionProps.whisperModel(),
                "--output_format", "json",
                "--output_dir", audioPath.getParent().toString()
        ));
        if (detectionProps.whisperTranslate()) {
            cmd.add("--task");
            cmd.add("translate");
        }

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.waitFor();

        log.info("Whisper: completed in {}ms", System.currentTimeMillis() - start);

        String baseName = audioPath.getFileName().toString().replaceFirst("\\.[^.]+$", "");
        Path jsonPath = audioPath.getParent().resolve(baseName + ".json");

        if (!jsonPath.toFile().exists()) {
            log.warn("Whisper produced no output for {}", audioPath);
            return "";
        }

        JsonNode root = objectMapper.readTree(jsonPath.toFile());
        String language = root.has("language") ? root.get("language").asText() : "unknown";
        String text = root.has("text") ? root.get("text").asText() : "";
        log.info("Whisper: language={}, text={}", language,
                text.substring(0, Math.min(80, text.length())));
        return text;
    }

    public String buildPrompt(String transcript, String context) {
        return promptTemplate
                .replace("{transcript}",
                        transcript.isEmpty() ? "No commentary transcript available." : transcript)
                .replace("{context}",
                        context.isEmpty() ? "This is the first window. No previous context." : context);
    }

    /**
     * Classify a window using vision (keyframes) + optional transcript + context.
     */
    public ClassificationResult classify(List<String> base64Frames, String transcript, String context) {
        String prompt = buildPrompt(transcript, context);
        return callOllama(prompt, base64Frames);
    }

    ClassificationResult callOllama(String prompt, List<String> base64Frames) {
        long start = System.currentTimeMillis();
        try {
            var requestMap = new LinkedHashMap<String, Object>();
            requestMap.put("model", ollamaProps.model());

            var message = new LinkedHashMap<String, Object>();
            message.put("role", "user");
            message.put("content", prompt);
            if (!base64Frames.isEmpty()) {
                message.put("images", base64Frames);
            }

            requestMap.put("messages", List.of(message));
            requestMap.put("stream", false);
            requestMap.put("think", false);

            var options = new LinkedHashMap<String, Object>();
            options.put("num_predict", ollamaProps.maxTokens());
            options.put("temperature", ollamaProps.temperature());
            requestMap.put("options", options);

            String requestBody = objectMapper.writeValueAsString(requestMap);

            WebClient client = WebClient.builder()
                    .baseUrl(ollamaProps.url())
                    .codecs(c -> c.defaultCodecs().maxInMemorySize(32 * 1024 * 1024)) // 32MB
                    .build();

            String response = client.post()
                    .header("Content-Type", "application/json")
                    .bodyValue(requestBody)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

            JsonNode root = objectMapper.readTree(response);
            String content = root.at("/message/content").asText();
            long elapsed = System.currentTimeMillis() - start;
            log.info("LLM responded in {}ms ({} frames), full response:\n{}", elapsed, base64Frames.size(), content);
            return parseResponse(content);
        } catch (Exception e) {
            log.error("LLM call failed ({}ms): {}", System.currentTimeMillis() - start, e.getMessage());
            return new ClassificationResult(List.of());
        }
    }

    public ClassificationResult parseResponse(String json) {
        try {
            String cleaned = json.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceAll("^```[a-z]*\\n?", "").replaceAll("\\n?```$", "").strip();
            }
            // Fix truncated JSON — try appending missing closing brackets
            if (!cleaned.endsWith("}")) {
                String fixed = cleaned;
                if (!fixed.contains("]}")) fixed += "]}";
                if (!fixed.endsWith("}")) fixed += "}";
                log.debug("Fixed truncated JSON: {}", fixed.substring(Math.max(0, fixed.length() - 50)));
                cleaned = fixed;
            }
            JsonNode root = objectMapper.readTree(cleaned);
            JsonNode eventsNode = root.get("events");
            if (eventsNode == null || !eventsNode.isArray()) {
                return new ClassificationResult(List.of());
            }
            List<ClassificationResult.Event> events = new ArrayList<>();
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
