package br.com.api.payment.pagamento;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity(name = "PAGAMENTO")
class Pagamento {

    @Id
    @Column(name = "ID_PAGAMENTO", nullable = false)
    private String idPagamento;

    @Column(name = "TXT_CPF_CNPJ_PAGADOR", nullable = false)
    private String cpfCnpjPagador;

    @Column(name = "TXT_CPF_CNPJ_RECEBEDOR", nullable = false)
    private String cpfCnpjRecebedor;

    @Column(name = "NUM_VALOR_TOTAL", nullable = false)
    private BigDecimal valorTotal;

    @Column(name = "TPO_PAGAMENTO", nullable = false)
    private String tipoPagamento;

    @Column(name = "DAT_CRIACAO_REGISTRO", nullable = false)
    private LocalDateTime dataCriacaoRegistro;

    @Column(name = "DAT_ALTERACAO_REGISTRO", nullable = false)
    private LocalDateTime dataAlteracaoRegistro;

    @PrePersist
    void prePersist() {
        var dataHoraAtual = LocalDateTime.now();
        dataCriacaoRegistro = dataHoraAtual;
        dataAlteracaoRegistro = dataHoraAtual;
    }

    @PreUpdate
    void preUpdate() {
        dataAlteracaoRegistro = LocalDateTime.now();
    }
}
