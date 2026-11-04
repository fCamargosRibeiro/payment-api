package br.com.api.payment.idempotencia.api;

// Evento gerado por um processamento; a idempotência o grava na outbox junto com o restante da transação
public record Evento(String topico, String chave, String payload) {
}
