package br.com.api.payment.pagamento.api;

import java.math.BigDecimal;

public record PagamentoResponse(String idPagamento, BigDecimal valorTotal) {
}
