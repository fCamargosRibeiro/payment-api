package br.com.api.payment.config;

import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.idempotencia.exception.IdempotenciaConflitoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(NegocioException.class)
    ProblemDetail handleNegocio(NegocioException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatus(), e.getMessage());
    }

    @ExceptionHandler(IdempotenciaConflitoException.class)
    ProblemDetail handleConflito(IdempotenciaConflitoException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }
}
