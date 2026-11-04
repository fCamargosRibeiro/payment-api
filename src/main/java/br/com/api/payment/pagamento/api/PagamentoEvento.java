package br.com.api.payment.pagamento.api;

import java.math.BigDecimal;

// Contrato do tópico de pagamento: cada feature interessada (notificação, contabilidade...) o consome com seu próprio grupo
public record PagamentoEvento(TipoEventoPagamento tipoEvento,
                              String idPagamento,
                              TipoPagamento tipoPagamento,
                              String cpfCnpjPagador,
                              String cpfCnpjRecebedor,
                              BigDecimal valorTotal) {
}
