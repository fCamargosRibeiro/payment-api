package br.com.api.payment.ted;

import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import br.com.api.payment.idempotencia.api.Operacao;
import br.com.api.payment.pagamento.api.PagamentoRequest;
import br.com.api.payment.pagamento.api.PagamentoResponse;
import br.com.api.payment.pagamento.api.TipoPagamento;
import br.com.api.payment.ted.api.TedComponent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "TED", description = "Pagamentos via TED")
@RequestMapping("/ted")
@RequiredArgsConstructor
class TedController {

    private final TedComponent tedComponent;
    private final IdempotenciaComponent idempotenciaComponent;

    @Operation(summary = "Realiza um pagamento via TED", description = "Idempotente pelo header X-Id-Transacao: repetir o pedido devolve a resposta original.")
    @ApiResponse(responseCode = "201", description = "Pagamento realizado (ou Reexecução de um já realizado)")
    @ApiResponse(responseCode = "400", description = "Header X-Id-Transacao ausente", content = @Content)
    @ApiResponse(responseCode = "409", description = "Conflito de Idempotência: o X-Id-Transacao já foi usado com outro conteúdo", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "Erro de Negócio", content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/pagamentos")
    public ResponseEntity<PagamentoResponse> pagar(@RequestHeader(IdempotenciaComponent.HEADER_ID_TRANSACAO) String idTransacao,
                                                   @RequestBody PagamentoRequest pagamentoRequest) {
        try {
            MDC.put("cpfCnpjPagador", pagamentoRequest.getCpfCnpjPagador());
            pagamentoRequest.adicionarTipoPagamento(TipoPagamento.TED);
            var resposta = idempotenciaComponent.executar(
                    idTransacao,
                    Operacao.PAGAMENTO_TED,
                    pagamentoRequest,
                    HttpStatus.CREATED,
                    PagamentoResponse.class,
                    () -> tedComponent.pagar(pagamentoRequest)
            );
            return ResponseEntity.status(resposta.status()).body(resposta.body());
        } finally {
            MDC.remove("cpfCnpjPagador");
        }
    }
}
