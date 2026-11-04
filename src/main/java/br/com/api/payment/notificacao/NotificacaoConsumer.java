package br.com.api.payment.notificacao;

import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import br.com.api.payment.idempotencia.exception.IdempotenciaConflitoException;
import br.com.api.payment.idempotencia.api.Operacao;
import br.com.api.payment.pagamento.api.PagamentoEvento;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.ShareAcknowledgment;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
@Slf4j
class NotificacaoConsumer {

    private final NotificacaoService notificacaoService;
    private final IdempotenciaComponent idempotenciaComponent;
    private final JsonMapper jsonMapper;

    @KafkaListener (
            topics = "#{@notificacaoPropertiesLoader.consumer.pagamento.topico}",
            concurrency = "#{@notificacaoPropertiesLoader.consumer.pagamento.concurrency}",
            groupId = "#{@notificacaoPropertiesLoader.consumer.pagamento.groupId}",
            containerFactory = "shareKafkaListenerContainerFactory",
            ackMode = "MANUAL"
    )
    public void consume(ConsumerRecord<String, String> record,
                        ShareAcknowledgment ack) {

        var header = record.headers().lastHeader(IdempotenciaComponent.HEADER_ID_TRANSACAO);
        if (header == null) {
            ack.reject();
            log.error("Evento de pagamento recebido sem o header {}", IdempotenciaComponent.HEADER_ID_TRANSACAO);
            return;
        }
        var idTransacao = new String(header.value(), StandardCharsets.UTF_8);

        try {
            var evento = jsonMapper.readValue(record.value(), PagamentoEvento.class);

            MDC.put("idPagamento", evento.idPagamento());
            log.debug("Processando notificação do evento {}", evento.tipoEvento());
            idempotenciaComponent.executar(idTransacao, Operacao.NOTIFICACAO, evento,
                    () -> notificacaoService.notificar(evento));
            ack.acknowledge();
        } catch (JacksonException e) {
            ack.reject();
            log.error("Erro ao desserializar evento de pagamento", e);
        } catch (IdempotenciaConflitoException e) {
            ack.reject();
            log.error("Conflito de Idempotência na notificação {}", idTransacao, e);
        } catch (NegocioException e) {
            // Erro de Negócio é definitivo e já foi registrado na idempotência
            ack.acknowledge();
            log.warn("Notificação {} recusada: {}", idTransacao, e.getMessage());
        } finally {
            MDC.remove("idPagamento");
        }
    }
}
