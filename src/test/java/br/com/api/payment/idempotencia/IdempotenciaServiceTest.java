package br.com.api.payment.idempotencia;

import br.com.api.payment.config.JacksonConfiguration;
import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.idempotencia.api.Operacao;
import br.com.api.payment.idempotencia.exception.IdempotenciaConflitoException;
import br.com.api.payment.outbox.api.OutboxComponent;
import br.com.api.payment.pagamento.api.PagamentoResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Regras da Reexecução e do Conflito, isoladas do banco: o registro existente vem do repositório mockado.
 */
@ExtendWith(MockitoExtension.class)
class IdempotenciaServiceTest {

    private static final Map<String, Object> PEDIDO = Map.of("valorTotal", 10);

    @Mock
    private IdempotenciaRepository idempotenciaRepository;

    @Mock
    private OutboxComponent outboxComponent;

    @Mock
    private PlatformTransactionManager transactionManager;

    private final JsonMapper jsonMapper = new JacksonConfiguration().jsonMapper();

    private IdempotenciaService idempotenciaService;

    @BeforeEach
    void setUp() {
        idempotenciaService = new IdempotenciaService(idempotenciaRepository, outboxComponent, jsonMapper, transactionManager);
    }

    @Test
    void dadoMesmoPedidoJaProcessado_quandoExecutar_deveDevolverRespostaRegistradaSemReprocessar() throws Exception {
        var checksum = checksumDaPrimeiraExecucao(Operacao.PAGAMENTO_PIX);
        var registrada = new PagamentoResponse("id-pagamento", new BigDecimal("10.00"));
        var idempotencia = idempotencia(checksum);
        idempotencia.registrarResposta(HttpStatus.CREATED, jsonMapper.writeValueAsString(registrada));
        when(idempotenciaRepository.findByIdTransacao("tx-1")).thenReturn(Optional.of(idempotencia));

        var resposta = idempotenciaService.executar("tx-1", Operacao.PAGAMENTO_PIX, PEDIDO, HttpStatus.CREATED,
                PagamentoResponse.class, () -> {
                    throw new AssertionError("não deveria reprocessar");
                });

        assertThat(resposta.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(resposta.body()).isEqualTo(registrada);
        verifyNoInteractions(outboxComponent, transactionManager);
    }

    @Test
    void dadoErroDeNegocioRegistrado_quandoReexecutar_deveLancarOMesmoErro() throws Exception {
        var idempotencia = idempotencia(checksumDaPrimeiraExecucao(Operacao.PAGAMENTO_PIX));
        var problema = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, "regra violada");
        idempotencia.registrarResposta(HttpStatus.UNPROCESSABLE_CONTENT, jsonMapper.writeValueAsString(problema));
        when(idempotenciaRepository.findByIdTransacao("tx-1")).thenReturn(Optional.of(idempotencia));

        assertThatThrownBy(() -> idempotenciaService.executar("tx-1", Operacao.PAGAMENTO_PIX, PEDIDO, HttpStatus.CREATED,
                PagamentoResponse.class, () -> null))
                .isInstanceOf(NegocioException.class)
                .hasMessage("regra violada")
                .extracting("status").isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
    }

    @Test
    void dadoMesmoIdTransacaoComOutroConteudo_quandoExecutar_deveLancarConflito() throws Exception {
        when(idempotenciaRepository.findByIdTransacao("tx-1")).thenReturn(Optional.of(idempotencia("checksum-de-outro-pedido")));

        assertThatThrownBy(() -> idempotenciaService.executar("tx-1", Operacao.PAGAMENTO_PIX, PEDIDO, HttpStatus.CREATED,
                PagamentoResponse.class, () -> null))
                .isInstanceOf(IdempotenciaConflitoException.class)
                .hasMessage("Id da Transação tx-1 já foi processado com outro conteúdo");
    }

    @Test
    void dadoMesmoPedidoEmOutraOperacao_quandoExecutar_deveLancarConflito() throws Exception {
        var checksumPix = checksumDaPrimeiraExecucao(Operacao.PAGAMENTO_PIX);
        when(idempotenciaRepository.findByIdTransacao("tx-1")).thenReturn(Optional.of(idempotencia(checksumPix)));

        assertThatThrownBy(() -> idempotenciaService.executar("tx-1", Operacao.PAGAMENTO_TED, PEDIDO, HttpStatus.CREATED,
                PagamentoResponse.class, () -> null))
                .isInstanceOf(IdempotenciaConflitoException.class);
    }

    @Test
    void dadoEventoKafkaJaProcessado_quandoExecutar_deveBuscarPelaChaveComOperacaoENaoReprocessar() throws Exception {
        var checksum = checksumDaPrimeiraExecucao(Operacao.NOTIFICACAO);
        when(idempotenciaRepository.findByIdTransacao("tx-1:NOTIFICACAO")).thenReturn(Optional.of(idempotencia(checksum)));

        idempotenciaService.executar("tx-1", Operacao.NOTIFICACAO, PEDIDO, () -> {
            throw new AssertionError("não deveria reprocessar");
        });

        verify(idempotenciaRepository).findByIdTransacao("tx-1:NOTIFICACAO");
        verifyNoInteractions(outboxComponent, transactionManager);
    }

    // Regra do checksum: SHA-256 de "<OPERACAO>:<pedido em JSON>"
    private String checksumDaPrimeiraExecucao(Operacao operacao) throws NoSuchAlgorithmException {
        var conteudo = operacao.name() + ":" + jsonMapper.writeValueAsString(PEDIDO);
        var digest = MessageDigest.getInstance("SHA-256").digest(conteudo.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private static Idempotencia idempotencia(String checksum) {
        return Idempotencia.builder()
                .idIdempotencia("id")
                .idTransacao("tx-1")
                .checksum(checksum)
                .build();
    }
}
