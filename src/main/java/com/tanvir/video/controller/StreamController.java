package com.tanvir.video.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanvir.video.model.StreamSession;
import com.tanvir.video.service.JobLauncherService;
import com.tanvir.video.service.StreamProcessingService;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamProcessingService processingService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();

    @Autowired(required = false)
    private JobLauncherService jobLauncher;

    @Value("${app.hls-dir}")
    private String hlsDir;

    public StreamController(StreamProcessingService processingService) {
        this.processingService = processingService;
    }

    /**
     * Launch a new stream as a Kubernetes Job. The Job runs the video-detector
     * container in --pipeline.stream mode, which processes the stream and
     * publishes detected events to Kafka.
     */
    @PostMapping("/launch")
    public ResponseEntity<Map<String, Object>> launchStream(@RequestBody Map<String, String> request) {
        if (jobLauncher == null) {
            return ResponseEntity.status(503).body(Map.of("error", "JobLauncherService not available"));
        }
        String url = request.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }
        try {
            return ResponseEntity.ok(jobLauncher.launch(url));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Stop a running stream by deleting its k8s Job.
     */
    @DeleteMapping("/launch/{sessionId}")
    public ResponseEntity<Map<String, Object>> stopLaunched(@PathVariable String sessionId) {
        if (jobLauncher == null) {
            return ResponseEntity.status(503).body(Map.of("error", "JobLauncherService not available"));
        }
        boolean deleted = jobLauncher.stop(sessionId);
        if (!deleted) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(Map.of("sessionId", sessionId, "status", "STOPPED"));
    }

    /**
     * Get status of a launched stream.
     */
    @GetMapping("/launch/{sessionId}")
    public ResponseEntity<Map<String, Object>> launchStatus(@PathVariable String sessionId) {
        if (jobLauncher == null) {
            return ResponseEntity.status(503).body(Map.of("error", "JobLauncherService not available"));
        }
        return ResponseEntity.ok(jobLauncher.status(sessionId));
    }

    @GetMapping("/ground-truth")
    public ResponseEntity<List<Map<String, Object>>> getGroundTruth(@RequestParam String clip) {
        try {
            String clipName = clip.contains("/") ?
                    Path.of(clip).getParent().getFileName().toString() : clip;
            Path gtPath = Path.of(hlsDir, clipName, "ground_truth.json");

            if (!Files.exists(gtPath)) {
                return ResponseEntity.ok(List.of());
            }

            JsonNode root = objectMapper.readTree(gtPath.toFile());
            JsonNode events = root.get("events");
            if (events == null) return ResponseEntity.ok(List.of());

            List<Map<String, Object>> result = new ArrayList<>();
            for (JsonNode e : events) {
                String label = e.get("label").asText();
                if (List.of("Goal", "Penalty", "Red card", "Yellow card", "Shots on target")
                        .contains(label)) {
                    result.add(Map.of(
                            "type", label,
                            "offset_sec", e.get("offset_sec").asDouble(),
                            "game_time", e.get("game_time").asText(),
                            "team", e.get("team").asText()
                    ));
                }
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            return ResponseEntity.ok(List.of());
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> startStream(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        if (url == null || url.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "url is required"));
        }

        StreamSession session = processingService.createSession(url);

        return ResponseEntity.ok(Map.of(
                "id", session.getId(),
                "status", session.getStatus().name(),
                "streamUrl", session.getStreamUrl()
        ));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String id) {
        StreamSession session = processingService.getSession(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok(Map.of(
                "id", session.getId(),
                "status", session.getStatus().name(),
                "streamUrl", session.getStreamUrl(),
                "windowsProcessed", session.getWindowsProcessed(),
                "candidatesFound", session.getCandidatesFound(),
                "eventsDetected", session.getDetectedEvents().size()
        ));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> stopStream(@PathVariable String id) {
        StreamSession session = processingService.getSession(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }

        processingService.stopSession(id);
        emitters.remove(id);
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    /**
     * SSE endpoint — prepares the session (segments the clip) and holds the connection
     * open for seek-triggered window analysis events.
     */
    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String id) {
        StreamSession session = processingService.getSession(id);
        if (session == null) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("Session not found"));
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(0L); // no timeout
        emitters.put(id, emitter);
        emitter.onCompletion(() -> emitters.remove(id));
        emitter.onTimeout(() -> emitters.remove(id));

        // Prepare session (segment the clip) — async
        processingService.prepareSession(id, message -> {
            try {
                emitter.send(SseEmitter.event().data(message));
            } catch (Exception e) {
                emitters.remove(id);
            }
        });

        return emitter;
    }

    /**
     * Seek endpoint — triggers analysis of the window containing the given time.
     * Called by the JS player on timeupdate events.
     */
    @PostMapping("/{id}/seek")
    public ResponseEntity<Map<String, Object>> seekToTime(
            @PathVariable String id, @RequestParam double time) {
        StreamSession session = processingService.getSession(id);
        if (session == null) {
            return ResponseEntity.notFound().build();
        }
        if (session.getStatus() != StreamSession.Status.READY) {
            return ResponseEntity.ok(Map.of("status", "not_ready"));
        }

        int windowIndex = (int) (time / 30); // 30s window duration
        session.setTargetWindow(windowIndex);

        SseEmitter emitter = emitters.get(id);
        if (emitter == null) {
            return ResponseEntity.ok(Map.of("status", "no_emitter"));
        }

        // Process the window asynchronously
        String result = processingService.processWindow(id, windowIndex, message -> {
            try {
                emitter.send(SseEmitter.event().data(message));
            } catch (Exception e) {
                // emitter may be closed
            }
        });

        return ResponseEntity.ok(Map.of(
                "status", result,
                "window", windowIndex
        ));
    }
}
