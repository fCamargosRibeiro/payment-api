package br.com.api.payment.pagamento;

import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.idempotencia.api.Evento;
import br.com.api.payment.idempotencia.api.ResultadoProcessamento;
import br.com.api.payment.pagamento.api.PagamentoComponent;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import br.com.api.payment.pagamento.api.PagamentoRequest;
import br.com.api.payment.pagamento.api.PagamentoResponse;
import br.com.api.payment.pagamento.api.TipoEventoPagamento;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
class PagamentoService implements PagamentoComponent {
    private final PagamentoFactory pagamentoFactory;
    private final PagamentoRepository pagamentoRepository;
    private final PagamentoPropertiesLoader pagamentoPropertiesLoader;
    private final JsonMapper jsonMapper;

    @Override
    public ResultadoProcessamento<PagamentoResponse> cadastrar(PagamentoRequest pagamentoRequest) {
        validar(pagamentoRequest);
        var pagamento = pagamentoFactory.fromRequest(
                pagamentoRequest
        );
        var pagamentoSalvo = pagamentoRepository.save(pagamento);
        var response = new PagamentoResponse(
                pagamentoSalvo.getIdPagamento(),
                pagamentoSalvo.getValorTotal()
        );
        return new ResultadoProcessamento<>(response, List.of(eventoConcluido(pagamentoRequest, pagamentoSalvo)));
    }

    private Evento eventoConcluido(PagamentoRequest pagamentoRequest, Pagamento pagamento) {
        var evento = new PagamentoEvento(
                TipoEventoPagamento.PAGAMENTO_CONCLUIDO,
                pagamento.getIdPagamento(),
                pagamentoRequest.getTipoPagamento(),
                pagamento.getCpfCnpjPagador(),
                pagamento.getCpfCnpjRecebedor(),
                pagamento.getValorTotal()
        );
        return new Evento(
                pagamentoPropertiesLoader.getProducer().getPagamento().getTopico(),
                pagamento.getIdPagamento(),
                jsonMapper.writeValueAsString(evento)
        );
    }

    private void validar(PagamentoRequest pagamentoRequest) {
        var valorTotal = pagamentoRequest.getValorTotal();
        if (valorTotal == null || valorTotal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new NegocioException("Valor total do pagamento deve ser maior que zero");
        }
        if (pagamentoRequest.getCpfCnpjPagador() != null
                && pagamentoRequest.getCpfCnpjPagador().equals(pagamentoRequest.getCpfCnpjRecebedor())) {
            throw new NegocioException("Pagador e recebedor não podem ser o mesmo");
        }
    }

}
