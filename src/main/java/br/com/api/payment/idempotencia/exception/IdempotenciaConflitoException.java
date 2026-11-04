package br.com.api.payment.idempotencia.exception;

public class IdempotenciaConflitoException extends RuntimeException {

    public IdempotenciaConflitoException(String idTransacao) {
        super("Id da Transação " + idTransacao + " já foi processado com outro conteúdo");
    }
}
