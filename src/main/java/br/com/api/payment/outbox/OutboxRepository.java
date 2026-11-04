package br.com.api.payment.outbox;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
interface OutboxRepository extends JpaRepository<Outbox, String> {
}
