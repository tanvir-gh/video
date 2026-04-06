package com.tanvir.video.controller;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanvir.video.model.StreamSession;
import com.tanvir.video.service.StreamProcessingService;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamProcessingService processingService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.hls-dir}")
    private String hlsDir;

    public StreamController(StreamProcessingService processingService) {
        this.processingService = processingService;
    }

    @GetMapping("/ground-truth")
    public ResponseEntity<List<Map<String, Object>>> getGroundTruth(@RequestParam String clip) {
        try {
            // clip is like "samples/hls/01_goal_2_arsenal/master.m3u8" or just "01_goal_2_arsenal"
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
        return ResponseEntity.ok(Map.of("status", "STOPPED"));
    }

    @GetMapping(value = "/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamEvents(@PathVariable String id) {
        StreamSession session = processingService.getSession(id);
        if (session == null) {
            SseEmitter emitter = new SseEmitter();
            emitter.completeWithError(new RuntimeException("Session not found"));
            return emitter;
        }

        SseEmitter emitter = new SseEmitter(0L);

        processingService.processStream(session.getId(), message -> {
            try {
                emitter.send(SseEmitter.event().data(message));
                // Close the emitter when pipeline is done
                if (message.contains("\"type\":\"done\"") || message.contains("\"type\":\"error\"")) {
                    emitter.complete();
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }
}
