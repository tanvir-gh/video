package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.tanvir.video.config.DetectionProperties;
import com.tanvir.video.model.ClassificationResult;
import com.tanvir.video.model.DetectedEvent;
import com.tanvir.video.model.StreamSession;

@Service
public class StreamProcessingService {

    private static final Logger log = LoggerFactory.getLogger(StreamProcessingService.class);

    private final DetectionProperties props;
    private final ClassifierService classifierService;
    private final ClipExtractorService clipExtractorService;
    private final String hlsDir;
    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private EventPublisher eventPublisher;

    public StreamProcessingService(
            DetectionProperties props,
            ClassifierService classifierService,
            ClipExtractorService clipExtractorService,
            @Value("${app.hls-dir}") String hlsDir) {
        this.props = props;
        this.classifierService = classifierService;
        this.clipExtractorService = clipExtractorService;
        this.hlsDir = hlsDir;
    }

    /** Create a session with a specific id (used for stream-worker mode). */
    public StreamSession createSessionWithId(String id, String streamUrl) {
        StreamSession session = new StreamSession(id, streamUrl);
        sessions.put(id, session);
        return session;
    }

    /** Set recovered event IDs so duplicate publishes are skipped. */
    public void setRecoveredEventIds(String sessionId, Set<String> eventIds) {
        StreamSession s = sessions.get(sessionId);
        if (s != null) {
            s.setRecoveredEventIds(eventIds);
        }
    }

    public StreamSession createSession(String streamUrl) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        StreamSession session = new StreamSession(id, streamUrl);
        sessions.put(id, session);
        return session;
    }

    public StreamSession getSession(String id) {
        return sessions.get(id);
    }

    public void stopSession(String id) {
        StreamSession session = sessions.get(id);
        if (session != null) {
            session.setStatus(StreamSession.Status.STOPPED);
            Process p = session.getIngestorProcess();
            if (p != null && p.isAlive()) {
                p.destroyForcibly();
            }
        }
    }

    // ---- Segmentation (called once per session) ----

    @Async("pipelineExecutor")
    public void prepareSession(String sessionId, Consumer<String> eventCallback) {
        StreamSession session = sessions.get(sessionId);
        if (session == null) return;

        log.info("=== Preparing session {} ===", sessionId);
        session.setStatus(StreamSession.Status.SEGMENTING);

        try {
            Path workDir = Path.of(props.workDir(), sessionId);
            Files.createDirectories(workDir);
            Path hlsOutput = Path.of(hlsDir, sessionId);
            Files.createDirectories(hlsOutput);
            Path sourcePath = Path.of(session.getStreamUrl());

            session.setWorkDir(workDir);
            session.setHlsOutput(hlsOutput);
            session.setSourcePath(sourcePath);

            eventCallback.accept("{\"type\":\"progress\",\"message\":\"Segmenting stream...\"}");
            List<StreamSession.WindowInfo> windows = segmentStream(sourcePath, workDir);
            session.setWindows(windows);

            session.setStatus(StreamSession.Status.READY);
            eventCallback.accept("{\"type\":\"ready\",\"windows\":" + windows.size() +
                    ",\"windowDuration\":" + props.windowDuration() + "}");
            log.info("Session {} ready: {} windows", sessionId, windows.size());

        } catch (Exception e) {
            log.error("Segmentation failed for session {}: {}", sessionId, e.getMessage(), e);
            session.setStatus(StreamSession.Status.ERROR);
            eventCallback.accept("{\"type\":\"error\",\"message\":\"" +
                    e.getMessage().replace("\"", "'") + "\"}");
        }
    }

    private List<StreamSession.WindowInfo> segmentStream(Path sourcePath, Path workDir) throws Exception {
        Files.createDirectories(workDir);
        int windowDuration = props.windowDuration();

        log.info("Segmenting: {} (window={}s)", sourcePath, windowDuration);

        ProcessBuilder videoPb = new ProcessBuilder(
                "ffmpeg", "-y",
                "-i", sourcePath.toString(),
                "-c", "copy",
                "-f", "segment",
                "-segment_time", String.valueOf(windowDuration),
                "-reset_timestamps", "1",
                workDir.resolve("window_%03d.ts").toString()
        );
        videoPb.redirectErrorStream(true);
        Process videoProc = videoPb.start();
        String ffmpegOutput = new String(videoProc.getInputStream().readAllBytes());
        int exitCode = videoProc.waitFor();
        if (exitCode != 0) {
            log.error("ffmpeg segment failed (exit {}): {}", exitCode,
                    ffmpegOutput.substring(Math.max(0, ffmpegOutput.length() - 300)));
        }

        List<StreamSession.WindowInfo> windows = new ArrayList<>();
        for (int i = 0; ; i++) {
            Path videoChunk = workDir.resolve(String.format("window_%03d.ts", i));
            if (!Files.exists(videoChunk)) break;
            windows.add(new StreamSession.WindowInfo(videoChunk, i));
        }

        log.info("Segmented into {} windows", windows.size());
        return windows;
    }

    // ---- Single window processing (called on demand) ----

    public String processWindow(String sessionId, int windowIndex, Consumer<String> eventCallback) {
        StreamSession session = sessions.get(sessionId);
        if (session == null || session.getWindows() == null) return "not_ready";
        if (windowIndex < 0 || windowIndex >= session.getWindows().size()) return "out_of_range";

        // Return cached result
        if (session.isWindowCached(windowIndex)) {
            return "cached";
        }

        // Skip if a newer window has been requested (stale request)
        // Only applies when target is explicitly set (web seek mode, not CLI sequential)
        if (session.getTargetWindow() >= 0 && session.getTargetWindow() != windowIndex) {
            log.debug("Skipping stale window {} (target is {})", windowIndex, session.getTargetWindow());
            return "skipped";
        }

        StreamSession.WindowInfo window = session.getWindows().get(windowIndex);

        try {
            long windowStart = System.currentTimeMillis();
            Path windowWorkDir = session.getWorkDir().resolve("w" + windowIndex);
            Files.createDirectories(windowWorkDir);

            int clipStartSec = windowIndex * props.windowDuration();
            int clipEndSec = clipStartSec + props.windowDuration();
            String timeRange = String.format("%d:%02d-%d:%02d",
                    clipStartSec / 60, clipStartSec % 60, clipEndSec / 60, clipEndSec % 60);

            eventCallback.accept("{\"type\":\"classifying\",\"window\":" + windowIndex +
                    ",\"timeRange\":\"" + timeRange + "\"}");

            // Step 1: Extract keyframes
            long t0 = System.currentTimeMillis();
            List<Path> framePaths = classifierService.extractKeyframes(window.videoPath(), windowWorkDir);
            long extractMs = System.currentTimeMillis() - t0;

            // Step 2: Encode frames as base64
            long t1 = System.currentTimeMillis();
            List<String> base64Frames = classifierService.encodeFrames(framePaths);
            long encodeMs = System.currentTimeMillis() - t1;

            // Step 3: Build context + classify
            String context = buildContext(session, windowIndex);
            long t3 = System.currentTimeMillis();
            ClassificationResult classification = classifierService.classify(base64Frames, context);
            long llmMs = System.currentTimeMillis() - t3;

            session.getWindowCache().put(windowIndex, classification);
            session.incrementWindowsProcessed();

            long windowElapsed = System.currentTimeMillis() - windowStart;
            long payloadKb = base64Frames.stream().mapToLong(String::length).sum() / 1024;
            log.info("TIMING w{}: extract={}ms encode={}ms llm={}ms TOTAL={}ms frames={} payload={}KB",
                    windowIndex, extractMs, encodeMs, llmMs, windowElapsed,
                    framePaths.size(), payloadKb);

            // Update running context
            if (classification.events().isEmpty()) {
                session.getRunningContext().append(
                        String.format("Window %d [%s]: no events\n", windowIndex, timeRange));
                eventCallback.accept("{\"type\":\"triage\",\"window\":" + windowIndex +
                        ",\"candidate\":false,\"reason\":\"no events detected (" + windowElapsed + "ms)\"" +
                        ",\"timeRange\":\"" + timeRange + "\"}");
                return "quiet";
            }

            for (var ev : classification.events()) {
                session.getRunningContext().append(
                        String.format("Window %d [%s]: %s (conf=%.2f)\n",
                                windowIndex, timeRange, ev.type(), ev.confidence()));
            }

            // Process detected events
            for (ClassificationResult.Event event : classification.events()) {
                if (event.confidence() < props.confidenceThreshold()) {
                    eventCallback.accept("{\"type\":\"low_confidence\",\"event\":\"" +
                            event.type() + "\",\"confidence\":" + event.confidence() +
                            ",\"window\":" + windowIndex + "}");
                    continue;
                }

                // Deduplicate consecutive windows
                String eventType = event.type().toLowerCase();
                if (eventType.equals(session.getLastEventType())
                        && windowIndex - session.getLastEventWindow() <= 2) {
                    eventCallback.accept("{\"type\":\"suppressed\",\"event\":\"" +
                            event.type() + "\",\"window\":" + windowIndex +
                            ",\"reason\":\"duplicate of window " + session.getLastEventWindow() + "\"}");
                    continue;
                }

                // Kafka-based dedup: if we already published this event ID for this
                // session (from a prior pod incarnation), skip re-publishing. Still
                // log it locally so benchmark counts are consistent.
                String eventId = EventPublisher.eventId(sessionId, windowIndex, event.type());
                boolean alreadyPublished = session.getRecoveredEventIds().contains(eventId);

                session.setLastEventType(eventType);
                session.setLastEventWindow(windowIndex);
                session.incrementCandidatesFound();

                double eventTime = windowIndex * props.windowDuration() + props.windowDuration() / 2.0;
                String slug = String.format("%s_%03d_%s", sessionId, windowIndex,
                        eventType.replace(" ", "_").replace("/", "_"));

                // Only extract the clip if we haven't already
                if (!alreadyPublished) {
                    clipExtractorService.extractClip(
                            session.getSourcePath(), eventTime, props.windowDuration(), slug, session.getHlsOutput());
                }

                DetectedEvent detected = new DetectedEvent(
                        event.type(), event.confidence(), event.description(),
                        windowIndex, eventTime, slug);
                session.addEvent(detected);

                // Publish to Kafka (idempotent: same eventId = same key = dedup)
                if (eventPublisher != null && !alreadyPublished) {
                    eventPublisher.publish(sessionId, detected);
                } else if (alreadyPublished) {
                    log.info("Skipping republish of {} (already in Kafka from prior run)", eventId);
                }

                eventCallback.accept("{\"type\":\"event\",\"event\":\"" + event.type() +
                        "\",\"confidence\":" + event.confidence() +
                        ",\"clip\":\"" + slug +
                        "\",\"timestamp\":" + eventTime +
                        ",\"description\":\"" + event.description().replace("\"", "'") +
                        "\",\"window\":" + windowIndex +
                        ",\"timeRange\":\"" + timeRange +
                        "\",\"elapsed\":" + windowElapsed + "}");
            }

            return "processed";

        } catch (Exception e) {
            log.error("Window {} failed: {}", windowIndex, e.getMessage(), e);
            eventCallback.accept("{\"type\":\"error\",\"message\":\"Window " + windowIndex +
                    " failed: " + e.getMessage().replace("\"", "'") + "\"}");
            return "error";
        }
    }

    private String buildContext(StreamSession session, int currentWindow) {
        String context = session.getRunningContext().toString();
        String[] lines = context.split("\n");
        if (lines.length > 3) {
            context = String.join("\n",
                    java.util.Arrays.copyOfRange(lines, lines.length - 3, lines.length));
        }
        return context;
    }

    // ---- Sequential processing (for CLI/benchmark mode) ----

    @Async("pipelineExecutor")
    public void processStream(String sessionId, Consumer<String> eventCallback) {
        StreamSession session = sessions.get(sessionId);
        if (session == null) return;

        log.info("=== Pipeline started for session {} ===", sessionId);
        log.info("Stream URL: {}", session.getStreamUrl());

        try {
            // Segment first
            Path workDir = Path.of(props.workDir(), sessionId);
            Files.createDirectories(workDir);
            Path hlsOutput = Path.of(hlsDir, sessionId);
            Files.createDirectories(hlsOutput);
            Path sourcePath = Path.of(session.getStreamUrl());

            session.setWorkDir(workDir);
            session.setHlsOutput(hlsOutput);
            session.setSourcePath(sourcePath);

            eventCallback.accept("{\"type\":\"progress\",\"message\":\"Segmenting stream...\"}");
            List<StreamSession.WindowInfo> windows = segmentStream(sourcePath, workDir);
            session.setWindows(windows);
            eventCallback.accept("{\"type\":\"progress\",\"message\":\"" + windows.size() + " windows to analyze\"}");

            // Process all windows sequentially
            for (int i = 0; i < windows.size(); i++) {
                if (session.getStatus() != StreamSession.Status.RUNNING) break;
                processWindow(sessionId, i, eventCallback);
            }

            session.setStatus(StreamSession.Status.STOPPED);
            eventCallback.accept("{\"type\":\"done\",\"events\":" + session.getDetectedEvents().size() +
                    ",\"windows\":" + session.getWindowsProcessed() +
                    ",\"candidates\":" + session.getCandidatesFound() + "}");

        } catch (Exception e) {
            log.error("Pipeline failed for session {}: {}", sessionId, e.getMessage(), e);
            session.setStatus(StreamSession.Status.ERROR);
            eventCallback.accept("{\"type\":\"error\",\"message\":\"" +
                    e.getMessage().replace("\"", "'") + "\"}");
        }
    }
}
