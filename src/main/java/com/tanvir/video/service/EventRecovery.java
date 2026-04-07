package com.tanvir.video.service;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * On worker startup, scans the 'detected-events' topic for records belonging
 * to a given sessionId and returns:
 *   1. resumeFromWindow — the next window to process (highest seen + 1)
 *   2. publishedEventIds — the set of internal eventIds already published
 *      (used to skip republishing if a window happens to be reprocessed)
 *
 * Uses a unique throwaway consumer group per call so we don't pin state.
 * Returns sentinel values (resumeFromWindow=0, empty set) if Kafka is
 * unreachable — recovery degrades gracefully to "start from scratch".
 */
@Service
public class EventRecovery {

    private static final Logger log = LoggerFactory.getLogger(EventRecovery.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    public record RecoveryState(int resumeFromWindow, Set<String> publishedEventIds) {
        public static RecoveryState empty() {
            return new RecoveryState(0, Set.of());
        }
    }

    public RecoveryState recover(String sessionId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "recovery-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");

        Set<String> publishedEventIds = new HashSet<>();
        int highestWindow = -1;
        String sessionPrefix = sessionId + "/w";

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitions = consumer.partitionsFor(EventPublisher.TOPIC);
            if (partitions == null || partitions.isEmpty()) {
                log.info("Recovery: topic {} not found, starting fresh for session {}",
                        EventPublisher.TOPIC, sessionId);
                return RecoveryState.empty();
            }

            List<TopicPartition> tps = partitions.stream()
                    .map(p -> new TopicPartition(EventPublisher.TOPIC, p.partition()))
                    .toList();
            consumer.assign(tps);
            consumer.seekToBeginning(tps);

            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(tps);
            log.info("Recovery: scanning {} partition(s) for session {}, end offsets: {}",
                    tps.size(), sessionId, endOffsets);

            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                for (var record : records) {
                    String key = record.key();
                    if (key == null || !key.startsWith(sessionPrefix)) continue;

                    // Parse window index from key: "sessionId/wNNNNNNNN"
                    int windowIndex;
                    try {
                        String windowPart = key.substring(sessionPrefix.length()); // "NNNNNNNN"
                        windowIndex = Integer.parseInt(windowPart);
                    } catch (Exception e) {
                        log.warn("Recovery: malformed key {}", key);
                        continue;
                    }

                    if (windowIndex > highestWindow) {
                        highestWindow = windowIndex;
                    }

                    // Extract event IDs from value (for cross-pod dedup)
                    try {
                        JsonNode root = objectMapper.readTree(record.value());
                        JsonNode events = root.get("events");
                        if (events != null && events.isArray()) {
                            for (JsonNode ev : events) {
                                if (ev.has("eventId")) {
                                    publishedEventIds.add(ev.get("eventId").asText());
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.warn("Recovery: failed to parse value for key {}: {}", key, e.getMessage());
                    }
                }

                boolean caughtUp = true;
                for (TopicPartition tp : tps) {
                    if (consumer.position(tp) < endOffsets.getOrDefault(tp, 0L)) {
                        caughtUp = false;
                        break;
                    }
                }
                if (caughtUp) break;
            }

            int resumeFrom = highestWindow + 1;
            log.info("Recovery: session {} — resume from window {}, {} previously-published event IDs",
                    sessionId, resumeFrom, publishedEventIds.size());
            return new RecoveryState(resumeFrom, publishedEventIds);

        } catch (Exception e) {
            log.warn("Recovery failed (Kafka unreachable?): {}. Starting fresh.", e.getMessage());
            return RecoveryState.empty();
        }
    }
}
