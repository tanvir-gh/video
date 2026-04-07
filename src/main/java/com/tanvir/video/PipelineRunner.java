package com.tanvir.video;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.config.OllamaProperties;
import com.tanvir.video.model.BenchmarkRun;
import com.tanvir.video.model.DetectedEvent;
import com.tanvir.video.model.StreamSession;
import com.tanvir.video.repository.BenchmarkRunRepository;
import com.tanvir.video.service.StreamProcessingService;

/**
 * CLI mode for processing clips and benchmarking.
 *
 * Single clip:
 *   ./gradlew bootRun --args="--pipeline.input=samples/hls/01_goal_2_arsenal/master.m3u8 --spring.main.web-application-type=none"
 *
 * Benchmark all clips with ground truth:
 *   ./gradlew bootRun --args="--pipeline.benchmark --spring.main.web-application-type=none"
 */
@Component
public class PipelineRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PipelineRunner.class);
    private static final double MATCH_TOLERANCE_SEC = 30.0;

    private final StreamProcessingService processingService;
    private final DetectionProperties detectionProps;
    private final OllamaProperties ollamaProps;
    private final BenchmarkRunRepository benchmarkRunRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.hls-dir}")
    private String hlsDir;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String kafkaBootstrapServers;

    public PipelineRunner(StreamProcessingService processingService,
                          DetectionProperties detectionProps,
                          OllamaProperties ollamaProps,
                          BenchmarkRunRepository benchmarkRunRepository) {
        this.processingService = processingService;
        this.detectionProps = detectionProps;
        this.ollamaProps = ollamaProps;
        this.benchmarkRunRepository = benchmarkRunRepository;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (args.containsOption("pipeline.benchmark")) {
            runBenchmark();
        } else if (args.containsOption("pipeline.input")) {
            List<String> inputs = args.getOptionValues("pipeline.input");
            logConfig();
            for (String input : inputs) {
                processClip(input);
            }
        } else if (args.containsOption("pipeline.kafka")) {
            // Kafka consumer mode: read ONE message from 'stream-requests', process it, exit.
            // This is what a KEDA-spawned Job pod runs.
            logConfig();
            consumeAndProcessOne();
        }
    }

    /**
     * Kafka consumer mode for KEDA ScaledJob pods.
     * Reads one message from 'stream-requests', processes the stream, commits, exits.
     */
    private void consumeAndProcessOne() throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaBootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "video-detector");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, "1");

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("stream-requests"));
            log.info("Kafka consumer subscribed to stream-requests, polling for one message...");

            long deadline = System.currentTimeMillis() + 60_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
                if (records.isEmpty()) continue;

                var record = records.iterator().next();
                log.info("Received message: key={}, value={}", record.key(), record.value());

                var node = objectMapper.readTree(record.value());
                String url = node.get("url").asText();

                processClip(url);

                consumer.commitSync();
                log.info("Committed offset, exiting.");
                return;
            }
            log.warn("No messages received within 60s, exiting.");
        }
    }

    private void logConfig() {
        log.info("=== Pipeline Config ===");
        log.info("Model: {}, Keyframes: {}@{}px, Whisper: {}, Threshold: {}",
                ollamaProps.model(), detectionProps.keyframeCount(),
                detectionProps.keyframeWidth(), detectionProps.whisperEnabled(),
                detectionProps.confidenceThreshold());
    }

    // ---- Single clip processing ----

    private void processClip(String inputPath) throws InterruptedException {
        Path path = Path.of(inputPath);
        if (!Files.exists(path)) {
            log.error("Input not found: {}", inputPath);
            return;
        }

        log.info("============================================================");
        log.info("Processing: {}", inputPath);

        long totalStart = System.currentTimeMillis();
        StreamSession session = processingService.createSession(inputPath);
        CountDownLatch done = new CountDownLatch(1);

        processingService.processStream(session.getId(), message -> {
            logStreamEvent(message, done);
        });

        done.await(30, TimeUnit.MINUTES);
        long totalElapsed = System.currentTimeMillis() - totalStart;
        log.info("Total: {}s for {}", String.format("%.1f", totalElapsed / 1000.0), inputPath);

        // Show ground truth comparison if available
        Path gtPath = path.getParent().resolve("ground_truth.json");
        int[] tpFpFn = new int[]{0, 0, 0};
        if (Files.exists(gtPath)) {
            tpFpFn = compareToGroundTruth(session, gtPath);
        }

        // Persist benchmark result
        persistBenchmark(path.getParent().getFileName().toString(),
                tpFpFn[0], tpFpFn[1], tpFpFn[2], totalElapsed, session.getWindowsProcessed());
    }

    // ---- Benchmark all clips ----

    private void runBenchmark() throws Exception {
        logConfig();
        log.info("=== BENCHMARK MODE ===");

        Path hlsPath = Path.of(hlsDir);
        List<Path> clips;
        try (Stream<Path> dirs = Files.list(hlsPath)) {
            clips = dirs.filter(Files::isDirectory)
                    .filter(d -> Files.exists(d.resolve("ground_truth.json")))
                    .filter(d -> Files.exists(d.resolve("master.m3u8")))
                    .sorted()
                    .toList();
        }

        if (clips.isEmpty()) {
            log.warn("No clips with ground_truth.json found in {}", hlsDir);
            return;
        }

        log.info("Found {} clips with ground truth\n", clips.size());

        int totalTp = 0, totalFp = 0, totalFn = 0;
        double totalTime = 0;
        int totalWindows = 0;

        for (Path clipDir : clips) {
            String inputPath = clipDir.resolve("master.m3u8").toString();
            log.info("============================================================");
            log.info("Clip: {}", clipDir.getFileName());

            long start = System.currentTimeMillis();
            StreamSession session = processingService.createSession(inputPath);
            CountDownLatch done = new CountDownLatch(1);

            processingService.processStream(session.getId(), message -> {
                logStreamEvent(message, done);
            });

            done.await(30, TimeUnit.MINUTES);
            double elapsed = (System.currentTimeMillis() - start) / 1000.0;
            totalTime += elapsed;
            totalWindows += session.getWindowsProcessed();

            log.info("Time: {}s, Windows: {}", String.format("%.1f", elapsed), session.getWindowsProcessed());

            // Compare
            Path gtPath = clipDir.resolve("ground_truth.json");
            int[] tpFpFn = compareToGroundTruth(session, gtPath);
            totalTp += tpFpFn[0];
            totalFp += tpFpFn[1];
            totalFn += tpFpFn[2];

            // Persist
            persistBenchmark(clipDir.getFileName().toString(),
                    tpFpFn[0], tpFpFn[1], tpFpFn[2],
                    (long)(elapsed * 1000), session.getWindowsProcessed());
            log.info("");
        }

        // Summary
        log.info("============================================================");
        log.info("BENCHMARK SUMMARY ({} clips)", clips.size());
        log.info("============================================================");
        double precision = totalTp + totalFp > 0 ? (double) totalTp / (totalTp + totalFp) : 0;
        double recall = totalTp + totalFn > 0 ? (double) totalTp / (totalTp + totalFn) : 0;
        double f1 = precision + recall > 0 ? 2 * precision * recall / (precision + recall) : 0;

        log.info("True Positives:  {}", totalTp);
        log.info("False Positives: {}", totalFp);
        log.info("False Negatives: {}", totalFn);
        log.info("Precision: {}", String.format("%.2f", precision));
        log.info("Recall:    {}", String.format("%.2f", recall));
        log.info("F1 Score:  {}", String.format("%.2f", f1));
        log.info("Total time: {}s for {} windows ({}s/window avg)",
                String.format("%.0f", totalTime), totalWindows,
                totalWindows > 0 ? String.format("%.1f", totalTime / totalWindows) : "N/A");
    }

    // ---- Ground truth comparison ----

    private int[] compareToGroundTruth(StreamSession session, Path gtPath) {
        try {
            JsonNode gt = objectMapper.readTree(gtPath.toFile());
            JsonNode events = gt.get("events");
            if (events == null) return new int[]{0, 0, 0};

            // Filter to high-value events only
            List<GtEvent> groundTruth = new ArrayList<>();
            for (JsonNode e : events) {
                String label = e.get("label").asText();
                if (List.of("Goal", "Penalty", "Red card", "Yellow card", "Shots on target")
                        .contains(label)) {
                    groundTruth.add(new GtEvent(label, e.get("offset_sec").asDouble()));
                }
            }

            List<DetectedEvent> detected = session.getDetectedEvents();

            // Match detected to ground truth within tolerance
            boolean[] gtMatched = new boolean[groundTruth.size()];
            int tp = 0, fp = 0;

            for (DetectedEvent det : detected) {
                boolean matched = false;
                for (int i = 0; i < groundTruth.size(); i++) {
                    if (gtMatched[i]) continue;
                    GtEvent g = groundTruth.get(i);
                    if (Math.abs(det.timestampSec() - g.offsetSec) <= MATCH_TOLERANCE_SEC
                            && typesMatch(det.type(), g.label)) {
                        tp++;
                        gtMatched[i] = true;
                        matched = true;
                        log.info("  TP: {} @ {}s matched GT {} @ {}s",
                                det.type(), String.format("%.0f", det.timestampSec()),
                                g.label, String.format("%.0f", g.offsetSec));
                        break;
                    }
                }
                if (!matched) {
                    fp++;
                    log.info("  FP: {} @ {}s (no matching ground truth)",
                            det.type(), String.format("%.0f", det.timestampSec()));
                }
            }

            int fn = 0;
            for (int i = 0; i < groundTruth.size(); i++) {
                if (!gtMatched[i]) {
                    fn++;
                    GtEvent g = groundTruth.get(i);
                    log.info("  FN: {} @ {}s (not detected)", g.label, String.format("%.0f", g.offsetSec));
                }
            }

            log.info("  Score: TP={} FP={} FN={} (of {} ground truth events)", tp, fp, fn, groundTruth.size());
            return new int[]{tp, fp, fn};
        } catch (Exception e) {
            log.warn("Failed to compare ground truth: {}", e.getMessage());
            return new int[]{0, 0, 0};
        }
    }

    private boolean typesMatch(String detected, String groundTruth) {
        String d = detected.toLowerCase();
        String g = groundTruth.toLowerCase();
        if (d.equals(g)) return true;
        if (d.contains("goal") && g.contains("goal")) return true;
        if (d.contains("penalty") && g.contains("penalty")) return true;
        if (d.contains("red") && g.contains("red")) return true;
        if (d.contains("yellow") && g.contains("yellow")) return true;
        if ((d.contains("save") || d.contains("shot")) && g.contains("shot")) return true;
        return false;
    }

    private record GtEvent(String label, double offsetSec) {}

    // ---- Persistence ----

    private void persistBenchmark(String clipName, int tp, int fp, int fn,
                                  long totalElapsedMs, int windowsProcessed) {
        try {
            BenchmarkRun run = new BenchmarkRun();
            run.setClipName(clipName);
            run.setModel(ollamaProps.model());
            run.setKeyframeCount(detectionProps.keyframeCount());
            run.setKeyframeWidth(detectionProps.keyframeWidth());
            run.setWindowDuration(detectionProps.windowDuration());
            run.setConfidenceThreshold(detectionProps.confidenceThreshold());
            run.setWhisperEnabled(detectionProps.whisperEnabled());
            run.setTp(tp);
            run.setFp(fp);
            run.setFn(fn);
            double precision = tp + fp > 0 ? (double) tp / (tp + fp) : 0;
            double recall = tp + fn > 0 ? (double) tp / (tp + fn) : 0;
            double f1 = precision + recall > 0 ? 2 * precision * recall / (precision + recall) : 0;
            run.setPrecision(precision);
            run.setRecall(recall);
            run.setF1(f1);
            run.setTotalTimeSec(totalElapsedMs / 1000.0);
            run.setWindowsProcessed(windowsProcessed);
            run.setAvgWindowTimeMs(windowsProcessed > 0 ? (int) (totalElapsedMs / windowsProcessed) : 0);
            run.setGitSha(getGitSha());

            benchmarkRunRepository.save(run);
            log.info("Benchmark persisted: {} (P={} R={} F1={})", clipName,
                    String.format("%.2f", precision), String.format("%.2f", recall), String.format("%.2f", f1));
        } catch (Exception e) {
            log.warn("Failed to persist benchmark: {}", e.getMessage());
        }
    }

    private String getGitSha() {
        try {
            Process p = new ProcessBuilder("git", "rev-parse", "--short", "HEAD")
                    .redirectErrorStream(true).start();
            String sha = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return sha.isEmpty() ? null : sha;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Event logging ----

    private void logStreamEvent(String message, CountDownLatch done) {
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
                        String.format("%.2f", node.get("confidence").asDouble()),
                        String.format("%.0f", node.get("timestamp").asDouble()),
                        node.has("description") ? node.get("description").asText() : "");
                case "low_confidence" -> log.info("[  low   ] {} (conf={})",
                        node.get("event").asText(),
                        String.format("%.2f", node.get("confidence").asDouble()));
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
    }
}
