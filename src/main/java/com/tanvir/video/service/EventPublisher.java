package com.tanvir.video.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanvir.video.model.DetectedEvent;

/**
 * Publishes per-window records to the Kafka 'detected-events' topic.
 * One record per window — even windows with no events get a record so
 * the recovery code can know "we processed up to window N".
 *
 * Key format: {sessionId}/w{8-digit-window-index}
 *   - sessionId/wNNNNNNNN
 *   - sortable, parseable, log-compaction friendly
 *
 * Value format: JSON with windowIndex, wallClock, and an events array
 * (possibly empty for quiet windows).
 *
 * Idempotency: Kafka's producer (enable.idempotence=true) handles in-flight
 * retries. Cross-pod recovery is handled by EventRecovery, which reads the
 * topic on startup, finds the highest window already processed, and tells
 * the pipeline to skip ahead.
 */
@Service
public class EventPublisher {

    public static final String TOPIC = "detected-events";

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public EventPublisher(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    /**
     * Compute the Kafka record key for a window. Same window = same key,
     * so log-compaction keeps only the latest version per window.
     */
    public static String windowKey(String sessionId, int windowIndex) {
        return sessionId + "/w" + String.format("%08d", windowIndex);
    }

    /**
     * Internal dedup ID for an event within a window. Used by the pipeline
     * to skip republishing the exact same event when a window is reprocessed.
     */
    public static String eventId(String sessionId, int windowIndex, String type) {
        String cleanType = type.toLowerCase().replace(" ", "_").replace("/", "_");
        return sessionId + "-w" + windowIndex + "-" + cleanType;
    }

    /**
     * Publish a window record to Kafka. Always called once per processed
     * window — empty events list means "quiet window heartbeat".
     */
    public void publishWindow(String sessionId, int windowIndex, List<DetectedEvent> events) {
        String key = windowKey(sessionId, windowIndex);
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("windowIndex", windowIndex);
            payload.put("wallClock", Instant.now().toString());

            List<Map<String, Object>> eventList = new ArrayList<>();
            for (DetectedEvent e : events) {
                Map<String, Object> ev = new LinkedHashMap<>();
                ev.put("eventId", eventId(sessionId, windowIndex, e.type()));
                ev.put("type", e.type());
                ev.put("confidence", e.confidence());
                ev.put("description", e.description());
                ev.put("timestampSec", e.timestampSec());
                ev.put("clipPath", e.clipPath());
                eventList.add(ev);
            }
            payload.put("events", eventList);

            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(TOPIC, key, json);

            if (events.isEmpty()) {
                log.debug("Published heartbeat: {}", key);
            } else {
                log.info("Published window {}: {} events ({})", key, events.size(),
                        events.stream().map(DetectedEvent::type).toList());
            }
        } catch (Exception e) {
            log.error("Failed to publish window record {}: {}", key, e.getMessage());
        }
    }
}
