package br.com.api.payment.pagamento;

import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import br.com.api.payment.pagamento.api.PagamentoResponse;
import org.junit.jupiter.api.Assertions;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PagamentoComponentHelper {

    public static final String CPF_PAGADOR = "12345678900";
    public static final String CPF_RECEBEDOR = "98765432100";

    private PagamentoComponentHelper() {
    }

    public static Map<String, Object> pedido(String valorTotal) {
        return pedido(CPF_PAGADOR, CPF_RECEBEDOR, valorTotal);
    }

    public static Map<String, Object> pedido(String cpfCnpjPagador, String cpfCnpjRecebedor, String valorTotal) {
        var pedido = new LinkedHashMap<String, Object>();
        pedido.put("cpfCnpjPagador", cpfCnpjPagador);
        pedido.put("cpfCnpjRecebedor", cpfCnpjRecebedor);
        pedido.put("valorTotal", new BigDecimal(valorTotal));
        return pedido;
    }

    public static ResponseEntity<String> pagar(String rota, String idTransacao, Map<String, Object> pedido, RestClient restClient) {
        var requisicao = restClient.post()
                .uri(rota)
                .contentType(MediaType.APPLICATION_JSON)
                .body(pedido);
        if (idTransacao != null) {
            requisicao = requisicao.header(IdempotenciaComponent.HEADER_ID_TRANSACAO, idTransacao);
        }
        return requisicao.retrieve().toEntity(String.class);
    }

    public static PagamentoResponse pagarComSucesso(String rota, String idTransacao, Map<String, Object> pedido,
                                                    RestClient restClient, JsonMapper jsonMapper) {
        var resposta = pagar(rota, idTransacao, pedido, restClient);
        Assertions.assertEquals(HttpStatus.CREATED, resposta.getStatusCode(), resposta.getBody());
        var pagamento = jsonMapper.readValue(resposta.getBody(), PagamentoResponse.class);
        Assertions.assertNotNull(pagamento.idPagamento());
        return pagamento;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> problema(ResponseEntity<String> resposta, JsonMapper jsonMapper) {
        return jsonMapper.readValue(resposta.getBody(), Map.class);
    }

    public static Map<String, Object> buscarPagamento(String idPagamento, JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForMap("SELECT * FROM PAGAMENTO WHERE ID_PAGAMENTO = ?", idPagamento);
    }

    public static Integer contarPagamentos(String idPagamento, JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM PAGAMENTO WHERE ID_PAGAMENTO = ?", Integer.class, idPagamento);
    }

    public static Map<String, Object> buscarIdempotencia(String idTransacao, JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForMap("SELECT * FROM IDEMPOTENCIA WHERE ID_TRANSACAO = ?", idTransacao);
    }

    public static Integer contarIdempotencias(String idTransacao, JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM IDEMPOTENCIA WHERE ID_TRANSACAO = ?", Integer.class, idTransacao);
    }

    public static Integer contarEventosNaOutbox(String idTransacao, JdbcTemplate jdbcTemplate) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM OUTBOX WHERE ID_TRANSACAO = ?", Integer.class, idTransacao);
    }
}
