package br.com.api.payment.idempotencia;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity(name = "IDEMPOTENCIA")
class Idempotencia {

    @Id
    @Column(name = "ID_IDEMPOTENCIA", nullable = false)
    private String idIdempotencia;

    @Column(name = "ID_TRANSACAO", nullable = false, unique = true)
    private String idTransacao;

    @Column(name = "TXT_CHECKSUM", nullable = false)
    private String checksum;

    // Resposta HTTP repetida nas Reexecuções; nula para entradas Kafka
    @Column(name = "NUM_STATUS_HTTP")
    private Integer statusHttp;

    @Column(name = "TXT_BODY", columnDefinition = "TEXT")
    private String body;

    @Column(name = "DAT_CRIACAO_REGISTRO", nullable = false)
    private LocalDateTime dataCriacaoRegistro;

    void registrarResposta(HttpStatus status, String body) {
        this.statusHttp = status.value();
        this.body = body;
    }

    @PrePersist
    void prePersist() {
        dataCriacaoRegistro = LocalDateTime.now();
    }
}
