package br.com.api.payment.pagamento.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public final class PagamentoRequest {

    private String cpfCnpjPagador;
    private String cpfCnpjRecebedor;
    private BigDecimal valorTotal;

    @JsonIgnore
    private TipoPagamento tipoPagamento;

    public PagamentoRequest adicionarTipoPagamento(TipoPagamento tipoPagamento) {
        this.tipoPagamento = tipoPagamento;
        return this;
    }

}
