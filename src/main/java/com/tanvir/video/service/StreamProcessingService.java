package com.tanvir.video.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public record WindowChunk(Path videoPath, Path audioPath, int index) {}

    public List<WindowChunk> segmentStream(Path sourcePath, Path workDir) throws Exception {
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

        // Only extract audio if whisper is enabled
        boolean needAudio = props.whisperEnabled();

        List<WindowChunk> windows = new ArrayList<>();
        for (int i = 0; ; i++) {
            Path videoChunk = workDir.resolve(String.format("window_%03d.ts", i));
            if (!Files.exists(videoChunk)) break;

            Path audioChunk = null;
            if (needAudio) {
                audioChunk = workDir.resolve(String.format("window_%03d.wav", i));
                ProcessBuilder audioPb = new ProcessBuilder(
                        "ffmpeg", "-y",
                        "-i", videoChunk.toString(),
                        "-vn", "-ar", "22050", "-ac", "1",
                        audioChunk.toString()
                );
                audioPb.redirectErrorStream(true);
                Process audioProc = audioPb.start();
                audioProc.getInputStream().readAllBytes();
                audioProc.waitFor();
            }

            windows.add(new WindowChunk(videoChunk, audioChunk, i));
        }

        log.info("Segmented into {} windows (audio extraction: {})", windows.size(), needAudio);
        return windows;
    }

    @Async("pipelineExecutor")
    public void processStream(String sessionId, Consumer<String> eventCallback) {
        StreamSession session = sessions.get(sessionId);
        if (session == null) {
            log.warn("processStream called with unknown sessionId: {}", sessionId);
            return;
        }

        log.info("=== Pipeline started for session {} ===", sessionId);
        log.info("Stream URL: {}", session.getStreamUrl());

        try {
            Path workDir = Path.of(props.workDir(), sessionId);
            Files.createDirectories(workDir);
            Path hlsOutput = Path.of(hlsDir, sessionId);
            Files.createDirectories(hlsOutput);

            Path sourcePath = Path.of(session.getStreamUrl());

            eventCallback.accept("{\"type\":\"progress\",\"message\":\"Segmenting stream...\"}");
            List<WindowChunk> windows = segmentStream(sourcePath, workDir);
            eventCallback.accept("{\"type\":\"progress\",\"message\":\"" + windows.size() + " windows to analyze\"}");

            // Track context across windows for LLM
            StringBuilder runningContext = new StringBuilder();
            String lastEventType = "";
            int lastEventWindow = -10;

            for (WindowChunk window : windows) {
                if (session.getStatus() != StreamSession.Status.RUNNING) break;

                long windowStart = System.currentTimeMillis();
                Path windowWorkDir = workDir.resolve("w" + window.index());
                Files.createDirectories(windowWorkDir);

                int clipStartSec = window.index() * props.windowDuration();
                int clipEndSec = clipStartSec + props.windowDuration();
                String timeRange = String.format("%d:%02d-%d:%02d",
                        clipStartSec / 60, clipStartSec % 60, clipEndSec / 60, clipEndSec % 60);

                eventCallback.accept("{\"type\":\"classifying\",\"window\":" + window.index() +
                        ",\"timeRange\":\"" + timeRange + "\"}");

                // Extract keyframes
                List<Path> framePaths = classifierService.extractKeyframes(window.videoPath(), windowWorkDir);
                List<String> base64Frames = classifierService.encodeFrames(framePaths);

                // Optional whisper transcript
                String transcript = "";
                if (window.audioPath() != null) {
                    transcript = classifierService.transcribeAudio(window.audioPath());
                }

                // Build context from recent windows (last 3)
                String context = runningContext.toString();
                // Keep only last 3 entries
                String[] contextLines = context.split("\n");
                if (contextLines.length > 3) {
                    context = String.join("\n",
                            java.util.Arrays.copyOfRange(contextLines, contextLines.length - 3, contextLines.length));
                }

                // Classify with vision + optional transcript + context
                ClassificationResult classification = classifierService.classify(base64Frames, transcript, context);
                session.incrementWindowsProcessed();

                long windowElapsed = System.currentTimeMillis() - windowStart;

                // Update running context for next window
                if (classification.events().isEmpty()) {
                    runningContext.append(String.format("Window %d: no events detected\n", window.index()));
                    eventCallback.accept("{\"type\":\"triage\",\"window\":" + window.index() +
                            ",\"candidate\":false,\"reason\":\"no events detected (" + windowElapsed + "ms)\"" +
                            ",\"timeRange\":\"" + timeRange + "\"}");
                    continue;
                } else {
                    for (var ev : classification.events()) {
                        runningContext.append(String.format("Window %d: %s (conf=%.2f) — %s\n",
                                window.index(), ev.type(), ev.confidence(), ev.description()));
                    }
                }

                for (ClassificationResult.Event event : classification.events()) {
                    if (event.confidence() < props.confidenceThreshold()) {
                        eventCallback.accept("{\"type\":\"low_confidence\",\"event\":\"" +
                                event.type() + "\",\"confidence\":" + event.confidence() +
                                ",\"window\":" + window.index() + "}");
                        continue;
                    }

                    // Deduplicate: suppress same event type in consecutive windows (likely replay)
                    String eventType = event.type().toLowerCase();
                    if (eventType.equals(lastEventType) && window.index() - lastEventWindow <= 2) {
                        log.info("Suppressed duplicate {} at window {} (previous at window {})",
                                event.type(), window.index(), lastEventWindow);
                        eventCallback.accept("{\"type\":\"suppressed\",\"event\":\"" +
                                event.type() + "\",\"window\":" + window.index() +
                                ",\"reason\":\"duplicate of window " + lastEventWindow + "\"}");
                        continue;
                    }

                    lastEventType = eventType;
                    lastEventWindow = window.index();
                    session.incrementCandidatesFound();

                    double eventTime = window.index() * props.windowDuration() + props.windowDuration() / 2.0;
                    String slug = String.format("%s_%03d_%s", sessionId, window.index(),
                            eventType.replace(" ", "_").replace("/", "_"));

                    clipExtractorService.extractClip(
                            sourcePath, eventTime, props.windowDuration(), slug, hlsOutput);

                    DetectedEvent detected = new DetectedEvent(
                            event.type(), event.confidence(), event.description(),
                            window.index(), eventTime, slug, transcript);
                    session.addEvent(detected);

                    eventCallback.accept("{\"type\":\"event\",\"event\":\"" + event.type() +
                            "\",\"confidence\":" + event.confidence() +
                            ",\"clip\":\"" + slug +
                            "\",\"timestamp\":" + eventTime +
                            ",\"description\":\"" + event.description().replace("\"", "'") +
                            "\",\"window\":" + window.index() +
                            ",\"elapsed\":" + windowElapsed + "}");
                }
            }

            if (session.getStatus() == StreamSession.Status.RUNNING) {
                session.setStatus(StreamSession.Status.STOPPED);
            }
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
