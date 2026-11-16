package br.com.api.payment.pix;

import br.com.api.payment.AbstractComponentTest;
import br.com.api.payment.KafkaComponentHelper;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import br.com.api.payment.pagamento.api.PagamentoResponse;
import br.com.api.payment.pagamento.api.TipoEventoPagamento;
import br.com.api.payment.pagamento.api.TipoPagamento;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static br.com.api.payment.pagamento.PagamentoComponentHelper.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class PixComponentTest extends AbstractComponentTest {

    private static final String ROTA = "/pix/pagamentos";

    @Test
    void dadoPedidoValido_quandoPagarViaPix_deveRegistrarPagamentoPublicarEventoENotificar() {
        var idTransacao = UUID.randomUUID().toString();

        var pagamento = pagarComSucesso(ROTA, idTransacao, pedido("150.00"), restClient, jsonMapper);

        assertThat(pagamento.valorTotal()).isEqualByComparingTo("150.00");
        assertThat(buscarPagamento(pagamento.idPagamento(), jdbcTemplate))
                .containsEntry("tpo_pagamento", "PIX")
                .containsEntry("txt_cpf_cnpj_pagador", CPF_PAGADOR)
                .containsEntry("txt_cpf_cnpj_recebedor", CPF_RECEBEDOR);
        assertThat(buscarIdempotencia(idTransacao, jdbcTemplate)).containsEntry("num_status_http", 201);
        assertThat(contarEventosNaOutbox(idTransacao, jdbcTemplate)).isEqualTo(1);

        // O Debezium publica o evento da outbox no tópico de pagamento, com o Id da Transação no header
        var mensagens = KafkaComponentHelper.consumirPorIdTransacao(TOPICO_PAGAMENTO, idTransacao, Duration.ofSeconds(20));
        assertThat(mensagens).hasSize(1);
        assertThat(mensagens.getFirst().key()).isEqualTo(pagamento.idPagamento());
        var evento = jsonMapper.readValue(mensagens.getFirst().value(), PagamentoEvento.class);
        assertThat(evento.tipoEvento()).isEqualTo(TipoEventoPagamento.PAGAMENTO_CONCLUIDO);
        assertThat(evento.tipoPagamento()).isEqualTo(TipoPagamento.PIX);
        assertThat(evento.idPagamento()).isEqualTo(pagamento.idPagamento());
        assertThat(evento.valorTotal()).isEqualByComparingTo(new BigDecimal("150.00"));

        // A notificação consome o evento e registra a própria idempotência ligada ao pedido original
        await().atMost(Duration.ofSeconds(60))
                .until(() -> contarIdempotencias(idTransacao + ":NOTIFICACAO", jdbcTemplate) == 1);
    }

    @Test
    void dadoMesmoPedido_quandoReexecutar_deveDevolverMesmaRespostaSemNovoPagamentoNemNovoEvento() {
        var idTransacao = UUID.randomUUID().toString();

        var primeira = pagarComSucesso(ROTA, idTransacao, pedido("80.00"), restClient, jsonMapper);
        var reexecucao = pagarComSucesso(ROTA, idTransacao, pedido("80.00"), restClient, jsonMapper);

        assertThat(reexecucao).isEqualTo(primeira);
        assertThat(contarPagamentos(primeira.idPagamento(), jdbcTemplate)).isEqualTo(1);
        assertThat(contarEventosNaOutbox(idTransacao, jdbcTemplate)).isEqualTo(1);
        await().atMost(Duration.ofSeconds(60))
                .until(() -> contarIdempotencias(idTransacao + ":NOTIFICACAO", jdbcTemplate) == 1);
        assertThat(KafkaComponentHelper.consumirPorIdTransacao(TOPICO_PAGAMENTO, idTransacao, Duration.ofSeconds(10)))
                .hasSize(1);
    }

    @Test
    void dadoIdTransacaoJaUsadoComOutroConteudo_quandoPagar_deveRetornarConflito() {
        var idTransacao = UUID.randomUUID().toString();
        pagarComSucesso(ROTA, idTransacao, pedido("10.00"), restClient, jsonMapper);

        var resposta = pagar(ROTA, idTransacao, pedido("99.00"), restClient);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(problema(resposta, jsonMapper))
                .containsEntry("status", 409)
                .containsEntry("detail", "Id da Transação " + idTransacao + " já foi processado com outro conteúdo");
        assertThat(contarEventosNaOutbox(idTransacao, jdbcTemplate)).isEqualTo(1);
    }

    @Test
    void dadoErroDeNegocio_quandoPagarEReexecutar_deveRetornarMesmoErroSemPagamentoNemEvento() {
        var idTransacao = UUID.randomUUID().toString();
        var pedidoInvalido = pedido(CPF_PAGADOR, CPF_PAGADOR, "10.00");

        var resposta = pagar(ROTA, idTransacao, pedidoInvalido, restClient);
        var reexecucao = pagar(ROTA, idTransacao, pedidoInvalido, restClient);

        for (var r : java.util.List.of(resposta, reexecucao)) {
            assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(problema(r, jsonMapper)).containsEntry("detail", "Pagador e recebedor não podem ser o mesmo");
        }
        assertThat(buscarIdempotencia(idTransacao, jdbcTemplate)).containsEntry("num_status_http", 422);
        assertThat(contarEventosNaOutbox(idTransacao, jdbcTemplate)).isZero();
    }

    @Test
    void dadoPedidoSemIdTransacao_quandoPagar_deveRetornarBadRequest() {
        var resposta = pagar(ROTA, null, pedido("10.00"), restClient);

        assertThat(resposta.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void dadoPedidosSimultaneosComMesmoIdTransacao_quandoPagar_deveRegistrarUmUnicoPagamento() throws Exception {
        var idTransacao = UUID.randomUUID().toString();
        var tarefas = new ArrayList<Callable<PagamentoResponse>>();
        for (int i = 0; i < 5; i++) {
            tarefas.add(() -> pagarComSucesso(ROTA, idTransacao, pedido("33.00"), restClient, jsonMapper));
        }

        var respostas = new ArrayList<PagamentoResponse>();
        try (var executor = Executors.newFixedThreadPool(tarefas.size())) {
            for (var futuro : executor.invokeAll(tarefas)) {
                respostas.add(futuro.get());
            }
        }

        assertThat(respostas).extracting(PagamentoResponse::idPagamento).containsOnly(respostas.getFirst().idPagamento());
        assertThat(contarPagamentos(respostas.getFirst().idPagamento(), jdbcTemplate)).isEqualTo(1);
        assertThat(contarIdempotencias(idTransacao, jdbcTemplate)).isEqualTo(1);
        assertThat(contarEventosNaOutbox(idTransacao, jdbcTemplate)).isEqualTo(1);
    }
}
