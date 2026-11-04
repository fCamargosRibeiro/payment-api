package br.com.api.payment.idempotencia.api;

import org.springframework.http.HttpStatus;

public record RespostaIdempotente<T>(HttpStatus status, T body) {
}
