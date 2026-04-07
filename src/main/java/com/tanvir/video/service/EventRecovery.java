package com.tanvir.video.service;

import java.time.Duration;
import java.util.Collections;
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
 * On worker startup, reads the 'detected-events' topic from the beginning
 * and returns the set of event IDs already published for a given sessionId.
 * The worker uses this set to skip re-publishing events during recovery.
 *
 * Uses a unique throwaway consumer group per call (no state pinning), just
 * does a one-shot read of everything currently in the topic, filters, exits.
 */
@Service
public class EventRecovery {

    private static final Logger log = LoggerFactory.getLogger(EventRecovery.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    /**
     * Read all events for this sessionId from Kafka and return their eventIds.
     * Returns an empty set if the topic is empty, doesn't exist, or Kafka is
     * unreachable (with a warning log — this means recovery is disabled, not
     * that recovery failed).
     */
    public Set<String> recoverEventIds(String sessionId) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "recovery-" + UUID.randomUUID());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000");
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, "10000");

        Set<String> recovered = new HashSet<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<PartitionInfo> partitions = consumer.partitionsFor(EventPublisher.TOPIC);
            if (partitions == null || partitions.isEmpty()) {
                log.info("Recovery: topic {} not found or empty, starting fresh for session {}",
                        EventPublisher.TOPIC, sessionId);
                return Collections.emptySet();
            }

            List<TopicPartition> tps = partitions.stream()
                    .map(p -> new TopicPartition(EventPublisher.TOPIC, p.partition()))
                    .toList();
            consumer.assign(tps);
            consumer.seekToBeginning(tps);

            // Find end offsets to know when we've caught up
            Map<TopicPartition, Long> endOffsets = consumer.endOffsets(tps);
            log.info("Recovery: reading {} partition(s), end offsets: {}", tps.size(), endOffsets);

            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));

                for (var record : records) {
                    try {
                        JsonNode node = objectMapper.readTree(record.value());
                        String recSessionId = node.has("sessionId") ? node.get("sessionId").asText() : null;
                        String eventId = node.has("eventId") ? node.get("eventId").asText() : record.key();
                        if (sessionId.equals(recSessionId) && eventId != null) {
                            recovered.add(eventId);
                        }
                    } catch (Exception e) {
                        log.warn("Recovery: failed to parse record at offset {}: {}",
                                record.offset(), e.getMessage());
                    }
                }

                // Check if we've read past the end
                boolean caughtUp = true;
                for (TopicPartition tp : tps) {
                    long pos = consumer.position(tp);
                    long end = endOffsets.getOrDefault(tp, 0L);
                    if (pos < end) {
                        caughtUp = false;
                        break;
                    }
                }
                if (caughtUp) break;
            }

            log.info("Recovery: found {} existing events for session {}", recovered.size(), sessionId);
            return recovered;

        } catch (Exception e) {
            log.warn("Recovery failed (Kafka unreachable?): {}. Starting fresh.", e.getMessage());
            return Collections.emptySet();
        }
    }
}
