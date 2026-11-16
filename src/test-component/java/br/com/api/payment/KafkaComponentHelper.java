package br.com.api.payment;

import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class KafkaComponentHelper {

    private KafkaComponentHelper() {
    }

    // Lê o tópico do início com um grupo descartável e devolve as mensagens cujo header X-Id-Transacao é o informado
    public static List<ConsumerRecord<String, String>> consumirPorIdTransacao(String topico,
                                                                           String idTransacao,
                                                                           Duration janela) {
        var config = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, AbstractComponentTest.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "componenttest-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        var encontradas = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            consumer.subscribe(List.of(topico));
            var fim = Instant.now().plus(janela);
            while (Instant.now().isBefore(fim)) {
                for (var registro : consumer.poll(Duration.ofMillis(500))) {
                    if (idTransacao.equals(idTransacao(registro))) {
                        encontradas.add(registro);
                    }
                }
            }
        }
        return encontradas;
    }

    // Lê o tópico do início até encontrar ao menos `minimo` mensagens do Id da Transação, ou até o tempo limite
    public static List<ConsumerRecord<String, String>> aguardarMensagens(String topico,
                                                                      String idTransacao,
                                                                      int minimo,
                                                                      Duration limite) {
        var config = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, AbstractComponentTest.bootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "componenttest-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class
        );
        var encontradas = new ArrayList<ConsumerRecord<String, String>>();
        try (var consumer = new KafkaConsumer<String, String>(config)) {
            consumer.subscribe(List.of(topico));
            var fim = Instant.now().plus(limite);
            while (encontradas.size() < minimo && Instant.now().isBefore(fim)) {
                for (var registro : consumer.poll(Duration.ofMillis(500))) {
                    if (idTransacao.equals(idTransacao(registro))) {
                        encontradas.add(registro);
                    }
                }
            }
        }
        return encontradas;
    }

    public static void publicar(String topico, String idTransacao, String chave, String payload) {
        var config = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, AbstractComponentTest.bootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        try (var producer = new KafkaProducer<String, String>(config)) {
            var registro = new ProducerRecord<>(topico, chave, payload);
            if (idTransacao != null) {
                registro.headers().add(IdempotenciaComponent.HEADER_ID_TRANSACAO, idTransacao.getBytes(StandardCharsets.UTF_8));
            }
            producer.send(registro).get();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao publicar no tópico " + topico, e);
        }
    }

    public static String idTransacao(ConsumerRecord<String, String> registro) {
        var header = registro.headers().lastHeader(IdempotenciaComponent.HEADER_ID_TRANSACAO);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
