package br.com.api.payment.ted.api;

import br.com.api.payment.idempotencia.api.ResultadoProcessamento;
import br.com.api.payment.pagamento.api.PagamentoRequest;
import br.com.api.payment.pagamento.api.PagamentoResponse;

public interface TedComponent {
    ResultadoProcessamento<PagamentoResponse> pagar(PagamentoRequest pagamentoRequest);
}
