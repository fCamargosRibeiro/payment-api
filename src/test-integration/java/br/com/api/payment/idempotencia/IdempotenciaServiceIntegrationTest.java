package br.com.api.payment.idempotencia;

import br.com.api.payment.config.JacksonConfiguration;
import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.idempotencia.api.Evento;
import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import br.com.api.payment.idempotencia.api.Operacao;
import br.com.api.payment.idempotencia.api.ResultadoProcessamento;
import br.com.api.payment.idempotencia.exception.IdempotenciaConflitoException;
import br.com.api.payment.outbox.api.OutboxComponent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Contexto reduzido de JPA contra um Postgres real: valida a fronteira de transação da idempotência
 * (commit e rollback conjuntos com a outbox) e a concorrência pelo índice único do Id da Transação.
 */
@ActiveProfiles("test")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@DataJpaTest(includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
        classes = {IdempotenciaComponent.class, OutboxComponent.class, JacksonConfiguration.class}))
class IdempotenciaServiceIntegrationTest {

    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Autowired
    private IdempotenciaComponent idempotenciaComponent;

    @Autowired
    private OutboxComponent outboxComponent;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void dadoPrimeiraExecucao_quandoExecutar_deveGravarIdempotenciaRespostaEEventosNaMesmaTransacao() {
        var idTransacao = UUID.randomUUID().toString();

        var resposta = idempotenciaComponent.executar(idTransacao, Operacao.PAGAMENTO_PIX, Map.of("valor", 10),
                HttpStatus.CREATED, String.class,
                () -> new ResultadoProcessamento<>("ok", List.of(new Evento("pagamento-v1", "chave", "{\"a\":1}"))));

        assertThat(resposta.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.body()).isEqualTo("ok");
        assertThat(jdbcTemplate.queryForMap("SELECT NUM_STATUS_HTTP, TXT_BODY FROM IDEMPOTENCIA WHERE ID_TRANSACAO = ?", idTransacao))
                .containsEntry("num_status_http", 201)
                .containsEntry("txt_body", "\"ok\"");
        assertThat(jdbcTemplate.queryForMap("SELECT TXT_TOPICO, TXT_CHAVE, TXT_BODY FROM OUTBOX WHERE ID_TRANSACAO = ?", idTransacao))
                .containsEntry("txt_topico", "pagamento-v1")
                .containsEntry("txt_chave", "chave")
                .containsEntry("txt_body", "{\"a\":1}");
    }

    @Test
    void dadoFalhaNoProcessamento_quandoExecutar_deveDesfazerTudoQueFoiGravado() {
        var idTransacao = UUID.randomUUID().toString();
        var idPagamento = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotenciaComponent.executar(idTransacao, Operacao.PAGAMENTO_PIX, Map.of(),
                HttpStatus.CREATED, String.class, () -> {
                    inserirPagamento(idPagamento);
                    throw new IllegalStateException("falha inesperada");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(contar("SELECT COUNT(*) FROM PAGAMENTO WHERE ID_PAGAMENTO = ?", idPagamento)).isZero();
        assertThat(contar("SELECT COUNT(*) FROM IDEMPOTENCIA WHERE ID_TRANSACAO = ?", idTransacao)).isZero();
    }

    @Test
    void dadoErroDeNegocio_quandoExecutarEReexecutar_deveGuardarErroSemOQueOProcessamentoGravou() {
        var idTransacao = UUID.randomUUID().toString();
        var idPagamento = UUID.randomUUID().toString();

        assertThatThrownBy(() -> idempotenciaComponent.executar(idTransacao, Operacao.PAGAMENTO_PIX, Map.of(),
                HttpStatus.CREATED, String.class, () -> {
                    inserirPagamento(idPagamento);
                    throw new NegocioException("regra violada");
                }))
                .isInstanceOf(NegocioException.class);

        assertThat(contar("SELECT COUNT(*) FROM PAGAMENTO WHERE ID_PAGAMENTO = ?", idPagamento)).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT NUM_STATUS_HTTP FROM IDEMPOTENCIA WHERE ID_TRANSACAO = ?",
                Integer.class, idTransacao)).isEqualTo(422);

        assertThatThrownBy(() -> idempotenciaComponent.executar(idTransacao, Operacao.PAGAMENTO_PIX, Map.of(),
                HttpStatus.CREATED, String.class, () -> {
                    throw new AssertionError("não deveria reprocessar");
                }))
                .isInstanceOf(NegocioException.class)
                .hasMessage("regra violada")
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void dadoIdTransacaoJaUsadoEmOutraOperacao_quandoExecutar_deveLancarConflito() {
        var idTransacao = UUID.randomUUID().toString();
        idempotenciaComponent.executar(idTransacao, Operacao.PAGAMENTO_PIX, Map.of("valor", 10),
                HttpStatus.CREATED, String.class, () -> new ResultadoProcessamento<>("ok", List.of()));

        assertThatThrownBy(() -> idempotenciaComponent.executar(idTransacao, Operacao.PAGAMENTO_TED, Map.of("valor", 10),
                HttpStatus.CREATED, String.class, () -> new ResultadoProcessamento<>("ok", List.of())))
                .isInstanceOf(IdempotenciaConflitoException.class);
    }

    @Test
    void dadoExecucoesSimultaneas_quandoExecutar_deveProcessarUmaVezEDevolverAMesmaResposta() throws Exception {
        var idTransacao = UUID.randomUUID().toString();
        var processamentos = new AtomicInteger();
        var tarefas = new ArrayList<Callable<String>>();
        for (int i = 0; i < 4; i++) {
            tarefas.add(() -> idempotenciaComponent.executar(idTransacao, Operacao.PAGAMENTO_PIX, Map.of("valor", 10),
                    HttpStatus.CREATED, String.class, () -> {
                        var numero = processamentos.incrementAndGet();
                        dormir();
                        return new ResultadoProcessamento<>("resposta-" + numero, List.of());
                    }).body());
        }

        var respostas = new ArrayList<String>();
        try (var executor = Executors.newFixedThreadPool(tarefas.size())) {
            for (var futuro : executor.invokeAll(tarefas)) {
                respostas.add(futuro.get());
            }
        }

        assertThat(processamentos).hasValue(1);
        assertThat(respostas).containsOnly("resposta-1");
        assertThat(contar("SELECT COUNT(*) FROM IDEMPOTENCIA WHERE ID_TRANSACAO = ?", idTransacao)).isEqualTo(1);
    }

    @Test
    void dadoEntradaKafka_quandoExecutar_deveRegistrarIdempotenciaPorOperacaoLigadaAoIdDeOrigem() {
        var idTransacao = UUID.randomUUID().toString();

        idempotenciaComponent.executar(idTransacao, Operacao.NOTIFICACAO, Map.of("evento", 1), List::of);

        assertThat(contar("SELECT COUNT(*) FROM IDEMPOTENCIA WHERE ID_TRANSACAO = ?", idTransacao + ":NOTIFICACAO")).isEqualTo(1);
        assertThat(contar("SELECT COUNT(*) FROM IDEMPOTENCIA WHERE ID_TRANSACAO = ?", idTransacao)).isZero();
    }

    @Test
    void dadoNenhumaTransacaoAberta_quandoPublicarNaOutbox_deveRecusar() {
        assertThatThrownBy(() -> outboxComponent.publicar("tx", "pagamento-v1", "chave", "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    private void inserirPagamento(String idPagamento) {
        jdbcTemplate.update("""
                INSERT INTO PAGAMENTO (ID_PAGAMENTO, TXT_CPF_CNPJ_PAGADOR, TXT_CPF_CNPJ_RECEBEDOR, TPO_PAGAMENTO,
                                       NUM_VALOR_TOTAL, DAT_CRIACAO_REGISTRO, DAT_ALTERACAO_REGISTRO)
                VALUES (?, '1', '2', 'PIX', 10, now(), now())""", idPagamento);
    }

    private Integer contar(String sql, String parametro) {
        return jdbcTemplate.queryForObject(sql, Integer.class, parametro);
    }

    private static void dormir() {
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
