package br.com.api.payment.outbox;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// Lida pelo Debezium (CDC): cada insert vira uma mensagem no tópico TXT_TOPICO (ADR 0002)
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Entity(name = "OUTBOX")
class Outbox {

    @Id
    @Column(name = "ID_OUTBOX", nullable = false)
    private String idOutbox;

    // Id da Transação que gerou o evento; enviado a quem consome no header X-Id-Transacao
    @Column(name = "ID_TRANSACAO", nullable = false)
    private String idTransacao;

    @Column(name = "TXT_TOPICO", nullable = false)
    private String topico;

    @Column(name = "TXT_CHAVE")
    private String chave;

    @Column(name = "TXT_BODY", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "DAT_CRIACAO_REGISTRO", nullable = false)
    private LocalDateTime dataCriacaoRegistro;

    @PrePersist
    void prePersist() {
        dataCriacaoRegistro = LocalDateTime.now();
    }
}
