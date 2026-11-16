package br.com.api.payment.notificacao;

import br.com.api.payment.config.JacksonConfiguration;
import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import br.com.api.payment.idempotencia.api.Operacao;
import br.com.api.payment.idempotencia.exception.IdempotenciaConflitoException;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import br.com.api.payment.pagamento.api.TipoEventoPagamento;
import br.com.api.payment.pagamento.api.TipoPagamento;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.autoconfigure.RefreshAutoConfiguration;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

/**
 * Contexto reduzido: só o consumer da notificação e a configuração Kafka, contra um broker real com Share Groups.
 * A idempotência e a simulação da notificação são substituídas por mocks.
 */
@ActiveProfiles("test")
@SpringBootTest(classes = {
        NotificacaoConsumer.class,
        NotificacaoKafkaConfig.class,
        NotificacaoPropertiesLoader.class,
        JacksonConfiguration.class
})
@EnableConfigurationProperties
@ImportAutoConfiguration({KafkaAutoConfiguration.class, RefreshAutoConfiguration.class})
class NotificacaoConsumerIntegrationTest {

    private static final String TOPICO = "pagamento-v1";
    private static final String GRUPO = "payment-api-test_notificacao";
    private static final long TIMEOUT_MS = 30_000;

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0")
            .withEnv("KAFKA_GROUP_COORDINATOR_REBALANCE_PROTOCOLS", "classic,consumer,streams,share")
            .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR", "1");

    static {
        KAFKA.start();
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPICO, 1, (short) 1))).all().get();
            var earliest = new AlterConfigOp(new ConfigEntry("share.auto.offset.reset", "earliest"), AlterConfigOp.OpType.SET);
            admin.incrementalAlterConfigs(Map.of(new ConfigResource(ConfigResource.Type.GROUP, GRUPO), List.of(earliest))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("notificacao.consumer.pagamento.topico", () -> TOPICO);
        registry.add("notificacao.consumer.pagamento.group-id", () -> GRUPO);
        registry.add("notificacao.consumer.pagamento.concurrency", () -> 1);
    }

    @MockitoBean
    private IdempotenciaComponent idempotenciaComponent;

    @MockitoBean
    private NotificacaoService notificacaoService;

    @Autowired
    private JsonMapper jsonMapper;

    @BeforeEach
    void limparMocks() {
        clearInvocations(idempotenciaComponent, notificacaoService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dadoEventoComIdTransacao_quandoConsumir_deveExecutarNotificacaoComIdempotencia() {
        var idTransacao = UUID.randomUUID().toString();
        var evento = evento();

        publicar(idTransacao, jsonMapper.writeValueAsString(evento));

        var processamento = ArgumentCaptor.forClass(Supplier.class);
        verify(idempotenciaComponent, timeout(TIMEOUT_MS))
                .executar(eq(idTransacao), eq(Operacao.NOTIFICACAO), eq(evento), processamento.capture());

        // O processamento entregue à idempotência é a notificação do evento recebido
        processamento.getValue().get();
        verify(notificacaoService).notificar(evento);
    }

    @Test
    void dadoEventoSemIdTransacao_quandoConsumir_deveDescartarSemExecutar() {
        publicar(null, jsonMapper.writeValueAsString(evento()));
        var sentinela = publicarSentinela();

        verify(idempotenciaComponent, timeout(TIMEOUT_MS)).executar(eq(sentinela), any(), any(), any());
        verify(idempotenciaComponent, never()).executar(eq(null), any(), any(), any());
    }

    @Test
    void dadoPayloadInvalido_quandoConsumir_deveDescartarSemExecutar() {
        var idTransacao = UUID.randomUUID().toString();
        publicar(idTransacao, "{ isso não é json");
        var sentinela = publicarSentinela();

        verify(idempotenciaComponent, timeout(TIMEOUT_MS)).executar(eq(sentinela), any(), any(), any());
        verify(idempotenciaComponent, never()).executar(eq(idTransacao), any(), any(), any());
    }

    @Test
    void dadoConflitoDeIdempotencia_quandoConsumir_deveRejeitarESeguirConsumindo() {
        var idTransacao = UUID.randomUUID().toString();
        doThrow(new IdempotenciaConflitoException(idTransacao))
                .when(idempotenciaComponent).executar(eq(idTransacao), any(), any(), any());

        publicar(idTransacao, jsonMapper.writeValueAsString(evento()));
        var sentinela = publicarSentinela();

        verify(idempotenciaComponent, timeout(TIMEOUT_MS)).executar(eq(idTransacao), any(), any(), any());
        verify(idempotenciaComponent, timeout(TIMEOUT_MS)).executar(eq(sentinela), any(), any(), any());
        assertThat(sentinela).isNotEqualTo(idTransacao);
    }

    @Test
    void dadoFalhaTransitoria_quandoConsumir_deveLiberarMensagemParaNovaEntrega() {
        var idTransacao = UUID.randomUUID().toString();
        doThrow(new IllegalStateException("banco indisponível"))
                .doNothing()
                .when(idempotenciaComponent).executar(eq(idTransacao), any(), any(), any());

        publicar(idTransacao, jsonMapper.writeValueAsString(evento()));

        verify(idempotenciaComponent, timeout(TIMEOUT_MS).times(2)).executar(eq(idTransacao), any(), any(), any());
    }

    private PagamentoEvento evento() {
        return new PagamentoEvento(TipoEventoPagamento.PAGAMENTO_CONCLUIDO, UUID.randomUUID().toString(),
                TipoPagamento.PIX, "12345678900", "98765432100", new BigDecimal("10.00"));
    }

    private String publicarSentinela() {
        var idTransacao = UUID.randomUUID().toString();
        publicar(idTransacao, jsonMapper.writeValueAsString(evento()));
        return idTransacao;
    }

    private void publicar(String idTransacao, String payload) {
        var config = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers(),
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class
        );
        try (var producer = new KafkaProducer<String, String>(config)) {
            var registro = new ProducerRecord<>(TOPICO, "chave", payload);
            if (idTransacao != null) {
                registro.headers().add(IdempotenciaComponent.HEADER_ID_TRANSACAO, idTransacao.getBytes(StandardCharsets.UTF_8));
            }
            producer.send(registro).get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
