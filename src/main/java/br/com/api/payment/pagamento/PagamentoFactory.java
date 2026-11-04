package br.com.api.payment.pagamento;

import br.com.api.payment.pagamento.api.PagamentoRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
@RequiredArgsConstructor
class PagamentoFactory {

    public Pagamento fromRequest(PagamentoRequest pagamentoRequest) {
        return Pagamento.builder()
                .idPagamento(UUID.randomUUID().toString())
                .cpfCnpjPagador(pagamentoRequest.getCpfCnpjPagador())
                .cpfCnpjRecebedor(pagamentoRequest.getCpfCnpjRecebedor())
                .valorTotal(pagamentoRequest.getValorTotal())
                .tipoPagamento(pagamentoRequest.getTipoPagamento().name())
                .build();
    }
}
