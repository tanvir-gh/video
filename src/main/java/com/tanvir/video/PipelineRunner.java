package com.tanvir.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OllamaProperties;
import com.tanvir.video.model.StreamSession;
import com.tanvir.video.service.StreamProcessingService;

/**
 * CLI mode: process a clip directly without the web UI.
 *
 * Usage:
 *   ./gradlew bootRun --args="--pipeline.input=samples/hls/01_goal_2_arsenal/master.m3u8"
 *
 * Runs the same pipeline code as the web UI but prints results to console.
 * If --pipeline.input is not provided, starts the web server normally.
 */
@Component
public class PipelineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);

    private final StreamProcessingService processingService;
    private final DetectionProperties detectionProps;
    private final OllamaProperties ollamaProps;

    public PipelineRunner(StreamProcessingService processingService,
                          DetectionProperties detectionProps,
                          OllamaProperties ollamaProps) {
        this.processingService = processingService;
        this.detectionProps = detectionProps;
        this.ollamaProps = ollamaProps;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption("pipeline.input")) {
            return; // Web mode — do nothing
        }

        List<String> inputs = args.getOptionValues("pipeline.input");
        log.info("=== CLI Pipeline Mode ===");
        log.info("Model: {}, Keyframes: {}@{}px, Whisper: {}",
                ollamaProps.model(), detectionProps.keyframeCount(),
                detectionProps.keyframeWidth(), detectionProps.whisperEnabled());

        for (String input : inputs) {
            processClip(input);
        }
    }

    private void processClip(String inputPath) throws InterruptedException {
        Path path = Path.of(inputPath);
        if (!Files.exists(path)) {
            log.error("Input not found: {}", inputPath);
            return;
        }

        log.info("\n{'='.repeat(60)}");
        log.info("Processing: {}", inputPath);

        // Load ground truth if available
        Path clipDir = path.getParent();
        Path gtPath = clipDir.resolve("ground_truth.json");
        if (Files.exists(gtPath)) {
            try {
                String gt = Files.readString(gtPath);
                log.info("Ground truth available: {}", gtPath);
            } catch (Exception e) {
                // ignore
            }
        }

        long totalStart = System.currentTimeMillis();
        var objectMapper = new com.fasterxml.jackson.databind.ObjectMapper();

        StreamSession session = processingService.createSession(inputPath);
        CountDownLatch done = new CountDownLatch(1);

        processingService.processStream(session.getId(), message -> {
            try {
                var node = objectMapper.readTree(message);
                String type = node.has("type") ? node.get("type").asText() : "";
                switch (type) {
                    case "progress" -> log.info("[progress] {}", node.get("message").asText());
                    case "classifying" -> log.info("[classify] Window {}", node.get("window").asInt());
                    case "triage" -> {
                        if (!node.get("candidate").asBoolean()) {
                            log.info("[  quiet ] Window {} — {}", node.get("window").asInt(),
                                    node.get("reason").asText());
                        }
                    }
                    case "event" -> log.info("[  EVENT ] {} (conf={}) @ {}s — {}",
                            node.get("event").asText(),
                            node.get("confidence").asDouble(),
                            node.get("timestamp").asDouble(),
                            node.has("description") ? node.get("description").asText() : "");
                    case "low_confidence" -> log.info("[  low   ] {} (conf={})",
                            node.get("event").asText(), node.get("confidence").asDouble());
                    case "error" -> log.error("[  ERROR ] {}", node.get("message").asText());
                    case "done" -> {
                        log.info("[  DONE  ] {} events, {} windows, {} candidates",
                                node.get("events").asInt(),
                                node.get("windows").asInt(),
                                node.get("candidates").asInt());
                        done.countDown();
                    }
                }
            } catch (Exception e) {
                log.info("[raw] {}", message);
            }
        });

        // Wait for pipeline to finish (up to 30 minutes)
        done.await(30, TimeUnit.MINUTES);
        long totalElapsed = System.currentTimeMillis() - totalStart;
        log.info("Total processing time: {}s for {}",
                String.format("%.1f", totalElapsed / 1000.0), inputPath);
    }
}
