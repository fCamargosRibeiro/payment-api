package br.com.api.payment.pagamento;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
interface PagamentoRepository extends JpaRepository<Pagamento, String> {
}
