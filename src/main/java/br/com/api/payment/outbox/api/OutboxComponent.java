package br.com.api.payment.outbox.api;

public interface OutboxComponent {
    // Usado só pela idempotência, dentro da transação dela; o Debezium publica após o commit
    void publicar(String idTransacao, String topico, String chave, String payload);
}
