package br.com.api.payment.pix.api;

import br.com.api.payment.idempotencia.api.ResultadoProcessamento;
import br.com.api.payment.pagamento.api.PagamentoRequest;
import br.com.api.payment.pagamento.api.PagamentoResponse;

public interface PixComponent {
    ResultadoProcessamento<PagamentoResponse> pagar(PagamentoRequest pagamentoRequest);
}
