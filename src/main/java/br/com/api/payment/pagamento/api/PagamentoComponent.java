package br.com.api.payment.pagamento.api;

import br.com.api.payment.idempotencia.api.ResultadoProcessamento;

public interface PagamentoComponent {
    // Devolve a resposta e o evento PAGAMENTO_CONCLUIDO, gravado na outbox pela execução idempotente
    ResultadoProcessamento<PagamentoResponse> cadastrar(PagamentoRequest pagamentoRequest);
}
