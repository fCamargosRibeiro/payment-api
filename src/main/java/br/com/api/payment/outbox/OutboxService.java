package br.com.api.payment.outbox;

import br.com.api.payment.outbox.api.OutboxComponent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
class OutboxService implements OutboxComponent {

    private final OutboxRepository outboxRepository;

    // MANDATORY: gravar fora da transação do negócio publicaria um evento sem garantia de que o negócio foi confirmado
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void publicar(String idTransacao, String topico, String chave, String payload) {
        outboxRepository.save(Outbox.builder()
                .idOutbox(UUID.randomUUID().toString())
                .idTransacao(idTransacao)
                .topico(topico)
                .chave(chave)
                .body(payload)
                .build());
    }
}
