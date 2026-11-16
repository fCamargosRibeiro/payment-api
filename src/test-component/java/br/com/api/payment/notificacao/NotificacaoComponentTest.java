package br.com.api.payment.notificacao;

import br.com.api.payment.AbstractComponentTest;
import br.com.api.payment.KafkaComponentHelper;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import br.com.api.payment.pagamento.api.TipoEventoPagamento;
import br.com.api.payment.pagamento.api.TipoPagamento;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static br.com.api.payment.pagamento.PagamentoComponentHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Entrada da notificação pelo tópico de pagamento: eventos publicados direto no Kafka, como chegariam do Debezium.
 */
class NotificacaoComponentTest extends AbstractComponentTest {

    @Test
    void dadoEventoEntregueDuasVezes_quandoConsumir_deveRegistrarUmaUnicaNotificacao() {
        var idTransacao = UUID.randomUUID().toString();
        var payload = evento("50.00");

        KafkaComponentHelper.publicar(TOPICO_PAGAMENTO, idTransacao, "chave", payload);
        KafkaComponentHelper.publicar(TOPICO_PAGAMENTO, idTransacao, "chave", payload);
        aguardarNotificacao(idTransacao);
        aguardarNotificacao(publicarSentinela());

        assertThat(contarIdempotencias(idTransacao + ":NOTIFICACAO", jdbcTemplate)).isEqualTo(1);
        assertThat(buscarIdempotencia(idTransacao + ":NOTIFICACAO", jdbcTemplate)).containsEntry("num_status_http", null);
    }

    @Test
    void dadoEventoComMesmoIdTransacaoEConteudoDiferente_quandoConsumir_deveManterNotificacaoOriginal() {
        var idTransacao = UUID.randomUUID().toString();
        KafkaComponentHelper.publicar(TOPICO_PAGAMENTO, idTransacao, "chave", evento("50.00"));
        aguardarNotificacao(idTransacao);
        var checksumOriginal = buscarIdempotencia(idTransacao + ":NOTIFICACAO", jdbcTemplate).get("txt_checksum");

        KafkaComponentHelper.publicar(TOPICO_PAGAMENTO, idTransacao, "chave", evento("999.00"));
        aguardarNotificacao(publicarSentinela());

        assertThat(buscarIdempotencia(idTransacao + ":NOTIFICACAO", jdbcTemplate))
                .containsEntry("txt_checksum", checksumOriginal);
    }

    @Test
    void dadoEventoSemIdTransacao_quandoConsumir_deveDescartarESeguirConsumindo() {
        KafkaComponentHelper.publicar(TOPICO_PAGAMENTO, null, "chave", evento("50.00"));

        aguardarNotificacao(publicarSentinela());
    }

    private String evento(String valorTotal) {
        return jsonMapper.writeValueAsString(new PagamentoEvento(
                TipoEventoPagamento.PAGAMENTO_CONCLUIDO,
                UUID.randomUUID().toString(),
                TipoPagamento.PIX,
                CPF_PAGADOR,
                CPF_RECEBEDOR,
                new BigDecimal(valorTotal)
        ));
    }

    // Evento válido publicado depois dos demais: quando ele é notificado, os anteriores já foram consumidos
    private String publicarSentinela() {
        var idTransacao = UUID.randomUUID().toString();
        KafkaComponentHelper.publicar(TOPICO_PAGAMENTO, idTransacao, "chave", evento("1.00"));
        return idTransacao;
    }

    private void aguardarNotificacao(String idTransacao) {
        await().atMost(Duration.ofSeconds(60))
                .until(() -> contarIdempotencias(idTransacao + ":NOTIFICACAO", jdbcTemplate) == 1);
    }
}
