package br.com.api.payment.pagamento;

import br.com.api.payment.config.JacksonConfiguration;
import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import br.com.api.payment.pagamento.api.PagamentoRequest;
import br.com.api.payment.pagamento.api.TipoEventoPagamento;
import br.com.api.payment.pagamento.api.TipoPagamento;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.AdditionalAnswers.returnsFirstArg;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PagamentoServiceTest {

    private static final String TOPICO = "pagamento-v1";

    @Mock
    private PagamentoRepository pagamentoRepository;

    private final JsonMapper jsonMapper = new JacksonConfiguration().jsonMapper();

    private PagamentoService pagamentoService;

    @BeforeEach
    void setUp() {
        var properties = new PagamentoPropertiesLoader();
        var producer = new PagamentoPropertiesLoader.Producer();
        var pagamento = new PagamentoPropertiesLoader.Producer.ProducerProperties();
        pagamento.setTopico(TOPICO);
        producer.setPagamento(pagamento);
        properties.setProducer(producer);
        pagamentoService = new PagamentoService(new PagamentoFactory(), pagamentoRepository, properties, jsonMapper);
    }

    @Test
    void dadoPedidoValido_quandoCadastrar_deveDevolverRespostaEEventoPagamentoConcluido() {
        when(pagamentoRepository.save(any())).then(returnsFirstArg());
        var pedido = new PagamentoRequest("12345678900", "98765432100", new BigDecimal("150.00"), TipoPagamento.TED);

        var resultado = pagamentoService.cadastrar(pedido);

        assertThat(resultado.resposta().valorTotal()).isEqualByComparingTo("150.00");
        assertThat(resultado.eventos()).hasSize(1);
        var evento = resultado.eventos().getFirst();
        assertThat(evento.topico()).isEqualTo(TOPICO);
        assertThat(evento.chave()).isEqualTo(resultado.resposta().idPagamento());

        var pagamentoEvento = jsonMapper.readValue(evento.payload(), PagamentoEvento.class);
        assertThat(pagamentoEvento).isEqualTo(new PagamentoEvento(
                TipoEventoPagamento.PAGAMENTO_CONCLUIDO,
                resultado.resposta().idPagamento(),
                TipoPagamento.TED,
                "12345678900",
                "98765432100",
                pagamentoEvento.valorTotal()
        ));
        assertThat(pagamentoEvento.valorTotal()).isEqualByComparingTo("150.00");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"0", "0.00", "-1"})
    void dadoValorTotalNaoPositivo_quandoCadastrar_deveLancarErroDeNegocioSemGravar(String valorTotal) {
        var pedido = new PagamentoRequest("1", "2", valorTotal == null ? null : new BigDecimal(valorTotal), TipoPagamento.PIX);

        assertThatThrownBy(() -> pagamentoService.cadastrar(pedido))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Valor total do pagamento deve ser maior que zero")
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
        verifyNoInteractions(pagamentoRepository);
    }

    @Test
    void dadoPagadorIgualAoRecebedor_quandoCadastrar_deveLancarErroDeNegocioSemGravar() {
        var pedido = new PagamentoRequest("12345678900", "12345678900", BigDecimal.TEN, TipoPagamento.PIX);

        assertThatThrownBy(() -> pagamentoService.cadastrar(pedido))
                .isInstanceOf(NegocioException.class)
                .hasMessage("Pagador e recebedor não podem ser o mesmo");
        verifyNoInteractions(pagamentoRepository);
    }
}
