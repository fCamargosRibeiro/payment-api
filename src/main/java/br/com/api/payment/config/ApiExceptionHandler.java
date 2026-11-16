package br.com.api.payment.config;

import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.idempotencia.exception.IdempotenciaConflitoException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Comparator;
import java.util.Map;

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

    // Cada violação vira um item de "erros" com o campo e a mensagem
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleValidacao(MethodArgumentNotValidException e) {
        var problema = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Requisição inválida");
        problema.setProperty("erros", e.getBindingResult().getFieldErrors().stream()
                .map(erro -> Map.of("campo", erro.getField(), "mensagem", String.valueOf(erro.getDefaultMessage())))
                .sorted(Comparator.comparing((Map<String, String> erro) -> erro.get("campo"))
                        .thenComparing(erro -> erro.get("mensagem")))
                .toList());
        return problema;
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    ProblemDetail handleHeaderAusente(MissingRequestHeaderException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Header " + e.getHeaderName() + " é obrigatório");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleCorpoIlegivel(HttpMessageNotReadableException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Corpo da requisição inválido");
    }
}
