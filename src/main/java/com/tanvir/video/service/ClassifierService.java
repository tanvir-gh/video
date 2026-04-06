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

        // Single ffmpeg pass: decode the segment ONCE, output all N frames using select filter
        // select='eq(n,F0)+eq(n,F1)+...' picks specific frame numbers in one decode
        // Compute frame numbers based on the actual fps of the source

        // Get source fps to compute exact frame numbers
        ProcessBuilder probe = new ProcessBuilder(
                "ffprobe", "-v", "quiet",
                "-select_streams", "v:0",
                "-show_entries", "stream=avg_frame_rate",
                "-of", "csv=p=0",
                videoPath.toString()
        );
        probe.redirectErrorStream(true);
        Process pp = probe.start();
        String fpsStr = new String(pp.getInputStream().readAllBytes()).trim();
        pp.waitFor();
        // fpsStr like "25/1" or "30000/1001"
        double sourceFps = 25.0; // sensible default
        try {
            String[] parts = fpsStr.split("/");
            sourceFps = Double.parseDouble(parts[0]) / Double.parseDouble(parts[1]);
        } catch (Exception e) { /* use default */ }

        // Pick N evenly-spaced frame indexes across the middle of the segment
        StringBuilder selectExpr = new StringBuilder();
        for (int i = 0; i < count; i++) {
            double ts = 2.0 + (duration - 4.0) * i / (count - 1);
            int frameNum = (int) (ts * sourceFps);
            if (i > 0) selectExpr.append("+");
            selectExpr.append("eq(n\\,").append(frameNum).append(")");
        }

        Path outputPattern = workDir.resolve("kf_%03d.png");
        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", videoPath.toString(),
                "-vf", String.format("select='%s',scale=%d:-1", selectExpr, width),
                "-vsync", "vfr",
                "-vframes", String.valueOf(count),
                outputPattern.toString()
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        p.getInputStream().readAllBytes();
        p.waitFor();

        List<Path> frames = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Path framePath = workDir.resolve(String.format("kf_%03d.png", i));
            if (Files.exists(framePath) && Files.size(framePath) > 0) {
                frames.add(framePath);
            }
        }
        log.debug("Extracted {} keyframes (single pass, {} fps)", frames.size(), sourceFps);
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
                String reason = e.has("reason") ? e.get("reason").asText()
                        : (e.has("description") ? e.get("description").asText() : "");
                events.add(new ClassificationResult.Event(
                        e.get("type").asText(),
                        e.get("confidence").asDouble(),
                        reason
                ));
            }
            return new ClassificationResult(events);
        } catch (Exception e) {
            log.warn("Failed to parse LLM response: {}", e.getMessage());
            return new ClassificationResult(List.of());
        }
    }
}
