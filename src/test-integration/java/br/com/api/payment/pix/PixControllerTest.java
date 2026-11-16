package br.com.api.payment.pix;

import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import br.com.api.payment.idempotencia.exception.IdempotenciaConflitoException;
import br.com.api.payment.pix.api.PixComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Camada web do PIX: validações do contrato e tradução das exceções para ProblemDetail pelo handler.
 */
@ActiveProfiles("test")
@WebMvcTest(PixController.class)
class PixControllerTest {

    private static final String ROTA = "/pix/pagamentos";
    private static final String PEDIDO_VALIDO = """
            {"cpfCnpjPagador":"12345678900","cpfCnpjRecebedor":"98765432100","valorTotal":150.00}""";

    @Autowired
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    private MockMvc mockMvc;

    @MockitoBean
    private IdempotenciaComponent idempotenciaComponent;

    @MockitoBean
    private PixComponent pixComponent;

    @ParameterizedTest(name = "{0}")
    @MethodSource("br.com.api.payment.pagamento.PagamentoRequestInvalidoSource#pedidosInvalidos")
    void dadoPedidoInvalido_quandoPagar_deveRetornarBadRequestComErroDoCampo(String cenario, String pedido,
                                                                            String campo, String mensagem) throws Exception {
        mockMvc.perform(post(ROTA)
                        .header(IdempotenciaComponent.HEADER_ID_TRANSACAO, "tx-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pedido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Requisição inválida"))
                .andExpect(jsonPath("$.erros[?(@.campo == '%s')].mensagem", campo).value(hasItem(mensagem)));

        verifyNoInteractions(idempotenciaComponent, pixComponent);
    }

    @Test
    void dadoPedidoVazio_quandoPagar_deveRetornarUmErroPorCampoObrigatorio() throws Exception {
        mockMvc.perform(post(ROTA)
                        .header(IdempotenciaComponent.HEADER_ID_TRANSACAO, "tx-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.erros", hasSize(3)))
                .andExpect(jsonPath("$.erros[*].campo").value(contains(
                        "cpfCnpjPagador", "cpfCnpjRecebedor", "valorTotal")));

        verifyNoInteractions(idempotenciaComponent, pixComponent);
    }

    @Test
    void dadoCorpoMalformado_quandoPagar_deveRetornarBadRequest() throws Exception {
        mockMvc.perform(post(ROTA)
                        .header(IdempotenciaComponent.HEADER_ID_TRANSACAO, "tx-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ isso não é json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Corpo da requisição inválido"));

        verifyNoInteractions(idempotenciaComponent, pixComponent);
    }

    @Test
    void dadoPedidoSemIdTransacao_quandoPagar_deveRetornarBadRequest() throws Exception {
        mockMvc.perform(post(ROTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PEDIDO_VALIDO))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Header X-Id-Transacao é obrigatório"));

        verifyNoInteractions(idempotenciaComponent, pixComponent);
    }

    @Test
    void dadoConflitoDeIdempotencia_quandoPagar_deveRetornarConflictComProblemDetail() throws Exception {
        when(idempotenciaComponent.executar(any(), any(), any(), any(), any(), any()))
                .thenThrow(new IdempotenciaConflitoException("tx-1"));

        mockMvc.perform(post(ROTA)
                        .header(IdempotenciaComponent.HEADER_ID_TRANSACAO, "tx-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PEDIDO_VALIDO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.detail").value("Id da Transação tx-1 já foi processado com outro conteúdo"));
    }

    @Test
    void dadoErroDeNegocio_quandoPagar_deveRetornarUnprocessableContentComProblemDetail() throws Exception {
        when(idempotenciaComponent.executar(any(), any(), any(), any(), any(), any()))
                .thenThrow(new NegocioException("Pagador e recebedor não podem ser o mesmo"));

        mockMvc.perform(post(ROTA)
                        .header(IdempotenciaComponent.HEADER_ID_TRANSACAO, "tx-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(PEDIDO_VALIDO))
                .andExpect(status().is(HttpStatus.UNPROCESSABLE_CONTENT.value()))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.detail").value("Pagador e recebedor não podem ser o mesmo"));
    }
}
