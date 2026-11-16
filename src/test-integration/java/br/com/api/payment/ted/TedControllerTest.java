package br.com.api.payment.ted;

import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import br.com.api.payment.ted.api.TedComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.hasItem;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Camada web do TED: o mesmo contrato do PIX precisa ser validado também neste endpoint.
 */
@ActiveProfiles("test")
@WebMvcTest(TedController.class)
class TedControllerTest {

    private static final String ROTA = "/ted/pagamentos";

    @Autowired
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    private MockMvc mockMvc;

    @MockitoBean
    private IdempotenciaComponent idempotenciaComponent;

    @MockitoBean
    private TedComponent tedComponent;

    @ParameterizedTest(name = "{0}")
    @MethodSource("br.com.api.payment.pagamento.PagamentoRequestInvalidoSource#pedidosInvalidos")
    void dadoPedidoInvalido_quandoPagar_deveRetornarBadRequestComErroDoCampo(String cenario, String pedido,
                                                                            String campo, String mensagem) throws Exception {
        mockMvc.perform(post(ROTA)
                        .header(IdempotenciaComponent.HEADER_ID_TRANSACAO, "tx-ted")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pedido))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Requisição inválida"))
                .andExpect(jsonPath("$.erros[?(@.campo == '%s')].mensagem", campo).value(hasItem(mensagem)));

        verifyNoInteractions(idempotenciaComponent, tedComponent);
    }

    @Test
    void dadoPedidoSemIdTransacao_quandoPagar_deveRetornarBadRequest() throws Exception {
        mockMvc.perform(post(ROTA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cpfCnpjPagador":"12345678900","cpfCnpjRecebedor":"98765432100","valorTotal":10.00}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Header X-Id-Transacao é obrigatório"));

        verifyNoInteractions(idempotenciaComponent, tedComponent);
    }
}
