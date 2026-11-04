package br.com.api.payment.idempotencia.api;

import java.util.List;

public record ResultadoProcessamento<T>(T resposta, List<Evento> eventos) {
}
