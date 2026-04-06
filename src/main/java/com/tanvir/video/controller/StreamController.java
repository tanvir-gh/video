package com.tanvir.video.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.tanvir.video.model.StreamSession;
import com.tanvir.video.service.StreamProcessingService;

@RestController
@RequestMapping("/api/streams")
public class StreamController {

    private final StreamProcessingService processingService;

    public StreamController(StreamProcessingService processingService) {
        this.processingService = processingService;
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
