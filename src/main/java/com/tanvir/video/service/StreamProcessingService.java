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
import com.tanvir.video.model.TriageResult;

@Service
public class StreamProcessingService {

    private static final Logger log = LoggerFactory.getLogger(StreamProcessingService.class);

    private final DetectionProperties props;
    private final TriageService triageService;
    private final ClassifierService classifierService;
    private final ClipExtractorService clipExtractorService;
    private final String hlsDir;
    private final Map<String, StreamSession> sessions = new ConcurrentHashMap<>();

    public StreamProcessingService(
            DetectionProperties props,
            TriageService triageService,
            ClassifierService classifierService,
            ClipExtractorService clipExtractorService,
            @Value("${app.hls-dir}") String hlsDir) {
        this.props = props;
        this.triageService = triageService;
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
        videoProc.getInputStream().readAllBytes();
        videoProc.waitFor();

        List<WindowChunk> windows = new ArrayList<>();
        for (int i = 0; ; i++) {
            Path videoChunk = workDir.resolve(String.format("window_%03d.ts", i));
            if (!Files.exists(videoChunk)) break;

            Path audioChunk = workDir.resolve(String.format("window_%03d.wav", i));
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

            windows.add(new WindowChunk(videoChunk, audioChunk, i));
        }

        log.info("Segmented {} into {} windows", sourcePath.getFileName(), windows.size());
        return windows;
    }

    @Async("pipelineExecutor")
    public void processStream(String sessionId, Consumer<String> eventCallback) {
        StreamSession session = sessions.get(sessionId);
        if (session == null) return;

        try {
            Path workDir = Path.of(props.workDir(), sessionId);
            Files.createDirectories(workDir);
            Path hlsOutput = Path.of(hlsDir, sessionId);
            Files.createDirectories(hlsOutput);

            Path sourcePath = Path.of(session.getStreamUrl());

            eventCallback.accept("{\"type\":\"progress\",\"message\":\"Segmenting stream...\"}");
            List<WindowChunk> windows = segmentStream(sourcePath, workDir);

            double baselineRms = 0.01;
            if (!windows.isEmpty()) {
                double sum = 0;
                int count = Math.min(2, windows.size());
                for (int i = 0; i < count; i++) {
                    sum += triageService.extractAudioRms(windows.get(i).audioPath());
                }
                baselineRms = sum / count;
                if (baselineRms <= 0) baselineRms = 0.01;
            }
            eventCallback.accept("{\"type\":\"progress\",\"message\":\"Baseline RMS: " +
                    String.format("%.4f", baselineRms) + "\"}");

            for (WindowChunk window : windows) {
                if (session.getStatus() != StreamSession.Status.RUNNING) break;

                TriageResult triage = triageService.triage(
                        window.index(), window.videoPath(), window.audioPath(), baselineRms, workDir);
                session.incrementWindowsProcessed();

                eventCallback.accept("{\"type\":\"triage\",\"window\":" + window.index() +
                        ",\"candidate\":" + triage.isCandidate() +
                        ",\"reason\":\"" + triage.reason().replace("\"", "'") + "\"}");

                if (!triage.isCandidate()) continue;
                session.incrementCandidatesFound();

                eventCallback.accept("{\"type\":\"classifying\",\"window\":" + window.index() + "}");

                String transcript = classifierService.transcribeAudio(window.audioPath());
                ClassificationResult classification = classifierService.classify(triage, transcript);

                for (ClassificationResult.Event event : classification.events()) {
                    if (event.confidence() < props.confidenceThreshold()) {
                        eventCallback.accept("{\"type\":\"low_confidence\",\"event\":\"" +
                                event.type() + "\",\"confidence\":" + event.confidence() + "}");
                        continue;
                    }

                    double eventTime = window.index() * props.windowDuration() + props.windowDuration() / 2.0;
                    String slug = String.format("%s_%03d_%s", sessionId, window.index(), event.type());

                    clipExtractorService.extractClip(
                            sourcePath, eventTime, props.windowDuration(), slug, hlsOutput);

                    DetectedEvent detected = new DetectedEvent(
                            event.type(), event.confidence(), event.description(),
                            window.index(), eventTime, slug, transcript);
                    session.addEvent(detected);

                    eventCallback.accept("{\"type\":\"event\",\"event\":\"" + event.type() +
                            "\",\"confidence\":" + event.confidence() +
                            ",\"clip\":\"" + slug + "\",\"timestamp\":" + eventTime + "}");
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
