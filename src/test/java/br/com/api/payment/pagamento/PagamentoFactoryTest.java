package br.com.api.payment.pagamento;

import br.com.api.payment.pagamento.api.PagamentoRequest;
import br.com.api.payment.pagamento.api.TipoPagamento;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PagamentoFactoryTest {

    private final PagamentoFactory pagamentoFactory = new PagamentoFactory();

    @ParameterizedTest
    @EnumSource(TipoPagamento.class)
    void dadoPedido_quandoCriarPagamento_deveMapearCamposEGerarId(TipoPagamento tipoPagamento) {
        var pedido = new PagamentoRequest("12345678900", "98765432100", new BigDecimal("150.00"), tipoPagamento);

        var pagamento = pagamentoFactory.fromRequest(pedido);

        assertThat(UUID.fromString(pagamento.getIdPagamento())).isNotNull();
        assertThat(pagamento.getCpfCnpjPagador()).isEqualTo("12345678900");
        assertThat(pagamento.getCpfCnpjRecebedor()).isEqualTo("98765432100");
        assertThat(pagamento.getValorTotal()).isEqualByComparingTo("150.00");
        assertThat(pagamento.getTipoPagamento()).isEqualTo(tipoPagamento.name());
    }

    @ParameterizedTest
    @EnumSource(TipoPagamento.class)
    void dadoDoisPedidos_quandoCriarPagamentos_deveGerarIdsDiferentes(TipoPagamento tipoPagamento) {
        var pedido = new PagamentoRequest("1", "2", BigDecimal.TEN, tipoPagamento);

        assertThat(pagamentoFactory.fromRequest(pedido).getIdPagamento())
                .isNotEqualTo(pagamentoFactory.fromRequest(pedido).getIdPagamento());
    }
}
