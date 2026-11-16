package br.com.api.payment.validacao;

import br.com.api.payment.AbstractComponentTest;
import br.com.api.payment.KafkaComponentHelper;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import br.com.api.payment.pagamento.api.TipoEventoPagamento;
import br.com.api.payment.pagamento.api.TipoPagamento;
import com.github.dockerjava.api.DockerClient;
import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.UUID;

import static br.com.api.payment.pagamento.PagamentoComponentHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Cenários de validação da PoC (artigo "Event-Driven Architecture + Outbox + Idempotência"), com falhas reais de
 * infraestrutura. O papel do Outbox Publisher é do Debezium. Cada cenário registra no log a evidência pedida
 * pelo artigo, em linhas iniciadas por "EVIDÊNCIA".
 * <p>
 * Roda por último ({@link ClassOrderer.OrderAnnotation}) porque pausa e reinicia containers compartilhados.
 */
@Order(Integer.MAX_VALUE)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CenariosValidacaoComponentTest extends AbstractComponentTest {

    private static final Logger log = LoggerFactory.getLogger(CenariosValidacaoComponentTest.class);
    private static final String ROTA = "/pix/pagamentos";
    private static final Duration LIMITE = Duration.ofMinutes(2);

    private final DockerClient docker = DockerClientFactory.instance().client();

    @Test
    @Order(1)
    void brokerIndisponivel_eventoFicaPendenteEEPublicadoAposRecuperacao() throws Exception {
        var idTransacao = UUID.randomUUID().toString();

        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            // Com o broker fora, o pagamento continua sendo aceito: o evento fica gravado na OUTBOX
            pagarComSucesso(ROTA, idTransacao, pedido("100.00"), restClient, jsonMapper);
            assertThat(contarEventosNaOutbox(idTransacao, jdbcTemplate)).isEqualTo(1);
            Thread.sleep(10_000);
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        var recuperacao = Instant.now();
        var mensagens = KafkaComponentHelper.aguardarMensagens(TOPICO_PAGAMENTO, idTransacao, 1, LIMITE);
        var tempoAtePublicacao = Duration.between(recuperacao, Instant.now());

        assertThat(mensagens).isNotEmpty();
        aguardarNotificacao(idTransacao);
        log.info("EVIDÊNCIA [Broker indisponível] eventos pendentes durante a indisponibilidade: 1; "
                + "tempo até a publicação após a recuperação: {} ms", tempoAtePublicacao.toMillis());
    }

    @Test
    @Order(2)
    void entregaDuplicada_restricaoUnicaImpedeRepeticaoDoEfeito() {
        var idTransacao = UUID.randomUUID().toString();
        var payload = jsonMapper.writeValueAsString(new PagamentoEvento(
                TipoEventoPagamento.PAGAMENTO_CONCLUIDO, UUID.randomUUID().toString(),
                TipoPagamento.PIX, CPF_PAGADOR, CPF_RECEBEDOR, new BigDecimal("10.00")));

        for (int i = 0; i < 3; i++) {
            KafkaComponentHelper.publicar(TOPICO_PAGAMENTO, idTransacao, "chave", payload);
        }
        aguardarNotificacao(idTransacao);

        var entregas = KafkaComponentHelper.aguardarMensagens(TOPICO_PAGAMENTO, idTransacao, 3, LIMITE).size();
        var efeitos = contarIdempotencias(idTransacao + ":NOTIFICACAO", jdbcTemplate);
        assertThat(entregas).isEqualTo(3);
        assertThat(efeitos).isEqualTo(1);
        log.info("EVIDÊNCIA [Entrega duplicada] entregas do mesmo identificador: {}; registros na idempotência: {}",
                entregas, efeitos);
    }

    @Test
    @Order(3)
    void reinicializacaoDoConsumer_transacaoRevertidaPermiteNovaTentativaEDuplicataNaoRepeteEfeito() {
        var idTransacao = UUID.randomUUID().toString();
        var chaveNotificacao = idTransacao + ":NOTIFICACAO";
        // Falha no commit da primeira tentativa do consumer, depois de a notificação ter sido processada
        jdbcTemplate.execute("CREATE SEQUENCE tentativas_consumer");
        jdbcTemplate.execute("""
                CREATE FUNCTION falhar_primeiro_commit() RETURNS trigger AS $$
                BEGIN
                    IF NEW.id_transacao = '%s' AND nextval('tentativas_consumer') = 1 THEN
                        RAISE EXCEPTION 'falha simulada no commit do consumer';
                    END IF;
                    RETURN NULL;
                END $$ LANGUAGE plpgsql""".formatted(chaveNotificacao));
        jdbcTemplate.execute("""
                CREATE CONSTRAINT TRIGGER falha_consumer AFTER INSERT ON idempotencia
                DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION falhar_primeiro_commit()""");
        try {
            pagarComSucesso(ROTA, idTransacao, pedido("200.00"), restClient, jsonMapper);
            aguardarNotificacao(idTransacao);
            var tentativas = tentativasDoConsumer();

            // Depois do commit, a mesma mensagem entregue de novo não repete o efeito
            var mensagem = KafkaComponentHelper.aguardarMensagens(TOPICO_PAGAMENTO, idTransacao, 1, LIMITE).getFirst();
            KafkaComponentHelper.publicar(TOPICO_PAGAMENTO, idTransacao, mensagem.key(), mensagem.value());
            aguardarNotificacao(publicarSentinela());

            assertThat(tentativas).isEqualTo(2L);
            assertThat(tentativasDoConsumer()).isEqualTo(tentativas);
            assertThat(contarIdempotencias(chaveNotificacao, jdbcTemplate)).isEqualTo(1);
            log.info("EVIDÊNCIA [Reinicialização do Consumer] tentativas de commit: {} (1 revertida, 1 confirmada); "
                    + "entregas após o commit: 1; registros na idempotência: 1", tentativas);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS falha_consumer ON idempotencia");
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS falhar_primeiro_commit()");
            jdbcTemplate.execute("DROP SEQUENCE IF EXISTS tentativas_consumer");
        }
    }

    @Test
    @Order(4)
    void reinicializacaoDoPublisher_publicacaoERetomadaAposRecuperacao() {
        var inicializacoes = inicializacoesDoDebezium();
        var ids = new ArrayList<String>();

        docker.stopContainerCmd(DEBEZIUM.getContainerId()).exec();
        for (int i = 0; i < 3; i++) {
            var idTransacao = UUID.randomUUID().toString();
            pagarComSucesso(ROTA, idTransacao, pedido("30.00"), restClient, jsonMapper);
            ids.add(idTransacao);
        }
        var pendentesAntes = ids.stream()
                .filter(id -> KafkaComponentHelper.aguardarMensagens(TOPICO_PAGAMENTO, id, 1, Duration.ofSeconds(3)).isEmpty())
                .count();
        assertThat(pendentesAntes).isEqualTo(3);
        assertThat(ids).allSatisfy(id -> assertThat(contarEventosNaOutbox(id, jdbcTemplate)).isEqualTo(1));

        docker.startContainerCmd(DEBEZIUM.getContainerId()).exec();
        var reinicio = Instant.now();
        await().atMost(LIMITE).until(() -> inicializacoesDoDebezium() > inicializacoes);
        ids.forEach(id -> assertThat(KafkaComponentHelper.aguardarMensagens(TOPICO_PAGAMENTO, id, 1, LIMITE)).isNotEmpty());
        var tempoAteRetomar = Duration.between(reinicio, Instant.now());
        ids.forEach(this::aguardarNotificacao);

        log.info("EVIDÊNCIA [Reinicialização do Publisher] eventos pendentes antes: {}; depois: 0; "
                + "tempo até publicar tudo após reiniciar: {} ms", pendentesAntes, tempoAteRetomar.toMillis());
    }

    @Test
    @Order(5)
    void falhaAposPublicacao_eventoRepublicadoEConsumerAplicaEfeitoUmaVez() {
        var idTransacao = UUID.randomUUID().toString();
        pagarComSucesso(ROTA, idTransacao, pedido("50.00"), restClient, jsonMapper);
        assertThat(KafkaComponentHelper.aguardarMensagens(TOPICO_PAGAMENTO, idTransacao, 1, LIMITE)).hasSize(1);
        aguardarNotificacao(idTransacao);

        // Queda abrupta depois de publicar, antes de o Debezium salvar a posição de leitura
        var inicializacoes = inicializacoesDoDebezium();
        docker.killContainerCmd(DEBEZIUM.getContainerId()).exec();
        docker.startContainerCmd(DEBEZIUM.getContainerId()).exec();
        await().atMost(LIMITE).until(() -> inicializacoesDoDebezium() > inicializacoes);

        var entregas = KafkaComponentHelper.aguardarMensagens(TOPICO_PAGAMENTO, idTransacao, 2, LIMITE).size();
        aguardarNotificacao(publicarSentinela());
        var efeitos = contarIdempotencias(idTransacao + ":NOTIFICACAO", jdbcTemplate);

        assertThat(entregas).isGreaterThanOrEqualTo(2);
        assertThat(efeitos).isEqualTo(1);
        log.info("EVIDÊNCIA [Falha após a publicação] entregas do evento: {}; efeitos aplicados: {}", entregas, efeitos);
    }

    private Long tentativasDoConsumer() {
        return jdbcTemplate.queryForObject("SELECT last_value FROM tentativas_consumer", Long.class);
    }

    private String publicarSentinela() {
        var idTransacao = UUID.randomUUID().toString();
        pagarComSucesso(ROTA, idTransacao, pedido("1.00"), restClient, jsonMapper);
        return idTransacao;
    }

    private void aguardarNotificacao(String idTransacao) {
        await().atMost(LIMITE).until(() -> contarIdempotencias(idTransacao + ":NOTIFICACAO", jdbcTemplate) == 1);
    }
}
