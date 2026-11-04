package br.com.api.payment.idempotencia;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
interface IdempotenciaRepository extends JpaRepository<Idempotencia, String> {
    Optional<Idempotencia> findByIdTransacao(String idTransacao);
}
