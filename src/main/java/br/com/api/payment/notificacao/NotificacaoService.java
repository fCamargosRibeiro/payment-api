package br.com.api.payment.notificacao;

import br.com.api.payment.idempotencia.api.Evento;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import br.com.api.payment.pagamento.api.TipoEventoPagamento;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@Slf4j
class NotificacaoService {

    // Devolve os eventos gerados pela notificação, gravados na outbox pela idempotência; a simulação não gera nenhum
    List<Evento> notificar(PagamentoEvento pagamentoEvento) {
        if (pagamentoEvento.tipoEvento() != TipoEventoPagamento.PAGAMENTO_CONCLUIDO) {
            log.debug("Evento de pagamento {} sem notificação", pagamentoEvento.tipoEvento());
            return List.of();
        }

        var notificacaoRequest = new NotificacaoRequest(
                pagamentoEvento.cpfCnpjPagador(),
                tipoNotificacao(pagamentoEvento),
                Map.of(
                        "idPagamento", pagamentoEvento.idPagamento(),
                        "cpfCnpjRecebedor", pagamentoEvento.cpfCnpjRecebedor(),
                        "valorTotal", pagamentoEvento.valorTotal()
                )
        );

        // Simula o envio da notificação ao usuário
        log.info("Notificando usuário [{}]: {}",
                notificacaoRequest.tipoNotificacao(),
                notificacaoRequest.tipoNotificacao().formatar(notificacaoRequest.atributos()));
        return List.of();
    }

    private TipoNotificacao tipoNotificacao(PagamentoEvento pagamentoEvento) {
        return switch (pagamentoEvento.tipoPagamento()) {
            case PIX -> TipoNotificacao.PAGAMENTO_PIX_CONCLUIDO;
            case TED -> TipoNotificacao.PAGAMENTO_TED_CONCLUIDO;
        };
    }
}
