package br.com.api.payment.ted;

import br.com.api.payment.idempotencia.api.ResultadoProcessamento;
import br.com.api.payment.pagamento.api.PagamentoComponent;
import br.com.api.payment.pagamento.api.PagamentoRequest;
import br.com.api.payment.pagamento.api.PagamentoResponse;
import br.com.api.payment.pagamento.api.TipoPagamento;
import br.com.api.payment.ted.api.TedComponent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
class TedService implements TedComponent {

    private final PagamentoComponent pagamentoComponent;

    @Override
    public ResultadoProcessamento<PagamentoResponse> pagar(PagamentoRequest pagamentoRequest) {
        var request = pagamentoRequest.adicionarTipoPagamento(TipoPagamento.TED);

        log.debug("Processando pagamento via TED");
        var resultado = pagamentoComponent.cadastrar(request);
        log.debug("Pagamento via TED realizado com sucesso: {}", resultado.resposta().idPagamento());

        return resultado;
    }
}
