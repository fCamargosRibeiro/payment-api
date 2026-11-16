package br.com.api.payment.ted;

import br.com.api.payment.AbstractComponentTest;
import br.com.api.payment.KafkaComponentHelper;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import br.com.api.payment.pagamento.api.TipoPagamento;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.UUID;

import static br.com.api.payment.pagamento.PagamentoComponentHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class TedComponentTest extends AbstractComponentTest {

    private static final String ROTA = "/ted/pagamentos";

    @Test
    void dadoPedidoValido_quandoPagarViaTed_deveRegistrarPagamentoPublicarEventoENotificar() {
        var idTransacao = UUID.randomUUID().toString();

        var pagamento = pagarComSucesso(ROTA, idTransacao, pedido("1500.00"), restClient, jsonMapper);

        assertThat(buscarPagamento(pagamento.idPagamento(), jdbcTemplate)).containsEntry("tpo_pagamento", "TED");
        var mensagens = KafkaComponentHelper.consumirPorIdTransacao(TOPICO_PAGAMENTO, idTransacao, Duration.ofSeconds(20));
        assertThat(mensagens).hasSize(1);
        assertThat(jsonMapper.readValue(mensagens.getFirst().value(), PagamentoEvento.class).tipoPagamento())
                .isEqualTo(TipoPagamento.TED);
        await().atMost(Duration.ofSeconds(60))
                .until(() -> contarIdempotencias(idTransacao + ":NOTIFICACAO", jdbcTemplate) == 1);
    }

    @Test
    void dadoIdTransacaoJaUsadoNoPix_quandoPagarViaTed_deveRetornarConflito() {
        var idTransacao = UUID.randomUUID().toString();
        pagarComSucesso("/pix/pagamentos", idTransacao, pedido("20.00"), restClient, jsonMapper);

        var resposta = pagar(ROTA, idTransacao, pedido("20.00"), restClient);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void dadoValorTotalNaoPositivo_quandoPagarViaTed_deveRecusarSemRegistrarIdempotencia() {
        var idTransacao = UUID.randomUUID().toString();

        var resposta = pagar(ROTA, idTransacao, pedido("0.00"), restClient);

        // Recusado pela validação do contrato, antes de chegar à idempotência: nada é gravado
        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(problema(resposta, jsonMapper)).containsEntry("detail", "Requisição inválida");
        assertThat(contarIdempotencias(idTransacao, jdbcTemplate)).isZero();
        assertThat(contarEventosNaOutbox(idTransacao, jdbcTemplate)).isZero();
    }
}
