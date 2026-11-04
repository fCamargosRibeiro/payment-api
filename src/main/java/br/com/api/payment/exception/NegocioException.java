package br.com.api.payment.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public class NegocioException extends RuntimeException {

    private final HttpStatus status;

    public NegocioException(String mensagem) {
        this(HttpStatus.UNPROCESSABLE_CONTENT, mensagem);
    }

    public NegocioException(HttpStatus status, String mensagem) {
        super(mensagem);
        this.status = status;
    }
}
