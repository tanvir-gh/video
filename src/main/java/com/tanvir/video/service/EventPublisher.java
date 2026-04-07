package com.tanvir.video.service;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tanvir.video.model.DetectedEvent;

/**
 * Publishes detected events to the Kafka 'detected-events' topic using the
 * idempotent writer pattern. Each event has a deterministic ID
 * {sessionId}-w{windowIndex}-{eventType} so that re-publishing (e.g., after
 * a pod restart reprocesses the same window) is a no-op from the downstream
 * consumer's perspective.
 *
 * Kafka's producer idempotence (enable.idempotence=true) guarantees that
 * retries don't duplicate at the partition level. Consumer-side dedup by
 * eventId on startup provides the stronger guarantee needed for recovery.
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
     * Compute the deterministic event ID for an event in a given window.
     * Same window + same type = same ID. Safe to republish.
     */
    public static String eventId(String sessionId, int windowIndex, String type) {
        String cleanType = type.toLowerCase().replace(" ", "_").replace("/", "_");
        return sessionId + "-w" + windowIndex + "-" + cleanType;
    }

    /**
     * Publish a detected event. The Kafka record key is the eventId
     * (so log-compaction will keep the latest version of each event).
     * The value is the full event JSON including the sessionId.
     */
    public void publish(String sessionId, DetectedEvent event) {
        String eventId = eventId(sessionId, event.windowIndex(), event.type());
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("eventId", eventId);
            payload.put("sessionId", sessionId);
            payload.put("type", event.type());
            payload.put("confidence", event.confidence());
            payload.put("description", event.description());
            payload.put("windowIndex", event.windowIndex());
            payload.put("timestampSec", event.timestampSec());
            payload.put("clipPath", event.clipPath());

            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send(TOPIC, eventId, json);
            log.info("Published event {}: {} (conf={})", eventId, event.type(),
                    String.format("%.2f", event.confidence()));
        } catch (Exception e) {
            log.error("Failed to publish event {}: {}", eventId, e.getMessage());
        }
    }
}
