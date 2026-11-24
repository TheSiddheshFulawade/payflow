package com.siddheshfulawade.payflow.risk.support;

import com.siddheshfulawade.payflow.contracts.Envelope;
import com.siddheshfulawade.payflow.contracts.MessageCodec;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/** Reads a whole topic from the beginning with a throw-away consumer, and produces raw records. */
public final class KafkaProbe {

    private final MessageCodec codec = new MessageCodec();

    public List<ConsumerRecord<String, String>> readRaw(String topic) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                Containers.KAFKA.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG,
                "probe-" + UUID.randomUUID(),
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                "false");
        try (KafkaConsumer<String, String> consumer =
                new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
            List<TopicPartition> partitions = consumer.partitionsFor(topic).stream()
                    .map(p -> new TopicPartition(topic, p.partition()))
                    .toList();
            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            List<ConsumerRecord<String, String>> out = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (partitions.stream().anyMatch(p -> consumer.position(p) < end.get(p))
                    && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(out::add);
            }
            return out;
        }
    }

    /** Decoded envelopes on a topic whose key is this payment id. */
    public List<Envelope> envelopes(String topic, UUID paymentId) {
        return readRaw(topic).stream()
                .filter(r -> paymentId.toString().equals(r.key()))
                .map(r -> codec.decode(r.value()))
                .toList();
    }

    public void produceRaw(String topic, String key, String value) {
        Map<String, Object> props = Map.of("bootstrap.servers", Containers.KAFKA.getBootstrapServers());
        try (KafkaProducer<String, String> producer =
                new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
            producer.send(new ProducerRecord<>(topic, key, value)).get();
            producer.flush();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
