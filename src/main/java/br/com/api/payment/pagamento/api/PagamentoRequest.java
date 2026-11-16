package br.com.api.payment.pagamento.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@AllArgsConstructor
@NoArgsConstructor
@Getter
public final class PagamentoRequest {

    private static final String CPF_OU_CNPJ = "\\d{11}|\\d{14}";

    @NotBlank(message = "cpfCnpjPagador é obrigatório")
    @Pattern(regexp = CPF_OU_CNPJ, message = "cpfCnpjPagador deve ter 11 (CPF) ou 14 (CNPJ) dígitos")
    private String cpfCnpjPagador;

    @NotBlank(message = "cpfCnpjRecebedor é obrigatório")
    @Pattern(regexp = CPF_OU_CNPJ, message = "cpfCnpjRecebedor deve ter 11 (CPF) ou 14 (CNPJ) dígitos")
    private String cpfCnpjRecebedor;

    @NotNull(message = "valorTotal é obrigatório")
    @Positive(message = "valorTotal deve ser maior que zero")
    @Digits(integer = 16, fraction = 2, message = "valorTotal deve ter no máximo 16 dígitos inteiros e 2 casas decimais")
    private BigDecimal valorTotal;

    @JsonIgnore
    private TipoPagamento tipoPagamento;

    public PagamentoRequest adicionarTipoPagamento(TipoPagamento tipoPagamento) {
        this.tipoPagamento = tipoPagamento;
        return this;
    }

}
