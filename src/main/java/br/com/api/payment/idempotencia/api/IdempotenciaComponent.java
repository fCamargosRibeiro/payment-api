package br.com.api.payment.idempotencia.api;

import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.function.Supplier;

public interface IdempotenciaComponent {

    String HEADER_ID_TRANSACAO = "X-Id-Transacao";

    // Entrada HTTP: numa única transação grava a idempotência, os eventos do processamento na outbox e a resposta
    <T> RespostaIdempotente<T> executar(String idTransacao,
                                        Operacao operacao,
                                        Object request,
                                        HttpStatus statusSucesso,
                                        Class<T> tipoResposta,
                                        Supplier<ResultadoProcessamento<T>> processamento);

    // Entrada Kafka: não há resposta a gravar; os eventos devolvidos pelo processamento vão para a outbox.
    // A idempotência é por Operação, para que várias features consumam o mesmo evento

    void executar(String idTransacao,
                  Operacao operacao,
                  Object request,
                  Supplier<List<Evento>> processamento);
}
