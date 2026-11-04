package br.com.api.payment.idempotencia;

import br.com.api.payment.exception.NegocioException;
import br.com.api.payment.idempotencia.api.Evento;
import br.com.api.payment.idempotencia.api.IdempotenciaComponent;
import br.com.api.payment.idempotencia.exception.IdempotenciaConflitoException;
import br.com.api.payment.idempotencia.api.Operacao;
import br.com.api.payment.idempotencia.api.RespostaIdempotente;
import br.com.api.payment.idempotencia.api.ResultadoProcessamento;
import br.com.api.payment.outbox.api.OutboxComponent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

@Service
@Slf4j
class IdempotenciaService implements IdempotenciaComponent {

    private final IdempotenciaRepository idempotenciaRepository;
    private final OutboxComponent outboxComponent;
    private final JsonMapper jsonMapper;
    private final TransactionTemplate transactionTemplate;

    IdempotenciaService(IdempotenciaRepository idempotenciaRepository,
                        OutboxComponent outboxComponent,
                        JsonMapper jsonMapper,
                        PlatformTransactionManager transactionManager) {
        this.idempotenciaRepository = idempotenciaRepository;
        this.outboxComponent = outboxComponent;
        this.jsonMapper = jsonMapper;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public <T> RespostaIdempotente<T> executar(String idTransacao,
                                               Operacao operacao,
                                               Object request,
                                               HttpStatus statusSucesso,
                                               Class<T> tipoResposta,
                                               Supplier<ResultadoProcessamento<T>> processamento) {
        return processar(idTransacao, operacao, request, statusSucesso, tipoResposta, processamento);
    }

    @Override
    public void executar(String idTransacao,
                         Operacao operacao,
                         Object request,
                         Supplier<List<Evento>> processamento) {
        // O mesmo evento é consumido por várias features: cada Operação tem sua própria idempotência sobre ele,
        // e a chave começa pelo Id da Transação de origem para relacionar os fluxos (ex.: tx-123:NOTIFICACAO)
        processar(idTransacao + ":" + operacao.name(), operacao, request, null, Void.class,
                () -> new ResultadoProcessamento<>(null, processamento.get()));
    }

    // statusSucesso nulo indica entrada sem resposta HTTP (Kafka)
    private <T> RespostaIdempotente<T> processar(String idTransacao,
                                                 Operacao operacao,
                                                 Object request,
                                                 HttpStatus statusSucesso,
                                                 Class<T> tipoResposta,
                                                 Supplier<ResultadoProcessamento<T>> processamento) {
        var checksum = checksum(operacao, request);

        var existente = idempotenciaRepository.findByIdTransacao(idTransacao);
        if (existente.isPresent()) {
            return reexecutar(existente.get(), checksum, tipoResposta);
        }

        try {
            return primeiraExecucao(idTransacao, checksum, statusSucesso, processamento);
        } catch (DataIntegrityViolationException e) {
            // Outra execução com o mesmo Id da Transação foi confirmada enquanto esta aguardava
            var concorrente = idempotenciaRepository.findByIdTransacao(idTransacao).orElseThrow(() -> e);
            return reexecutar(concorrente, checksum, tipoResposta);
        }
    }

    // Idempotência, processamento, eventos na outbox e resposta: uma única transação, desfeita por inteiro se algo falhar
    private <T> RespostaIdempotente<T> primeiraExecucao(String idTransacao,
                                                        String checksum,
                                                        HttpStatus statusSucesso,
                                                        Supplier<ResultadoProcessamento<T>> processamento) {
        try {
            return transactionTemplate.execute(status -> {
                var idempotencia = idempotenciaRepository.saveAndFlush(novaIdempotencia(idTransacao, checksum));
                var resultado = processamento.get();
                resultado.eventos().forEach(evento ->
                        outboxComponent.publicar(idTransacao, evento.topico(), evento.chave(), evento.payload()));
                if (statusSucesso != null) {
                    idempotencia.registrarResposta(statusSucesso, jsonMapper.writeValueAsString(resultado.resposta()));
                }
                return new RespostaIdempotente<>(statusSucesso, resultado.resposta());
            });
        } catch (NegocioException e) {
            registrarErroNegocio(idTransacao, checksum, statusSucesso != null, e);
            throw e;
        }
    }

    // Os eventos já foram publicados pelo Debezium na primeira execução; só a resposta é repetida
    private <T> RespostaIdempotente<T> reexecutar(Idempotencia idempotencia, String checksum, Class<T> tipoResposta) {
        var idTransacao = idempotencia.getIdTransacao();
        if (!idempotencia.getChecksum().equals(checksum)) {
            throw new IdempotenciaConflitoException(idTransacao);
        }

        log.debug("Reexecução do Id da Transação {}", idTransacao);
        if (idempotencia.getStatusHttp() == null) {
            return null;
        }

        var status = HttpStatus.valueOf(idempotencia.getStatusHttp());
        if (status.isError()) {
            var problema = jsonMapper.readValue(idempotencia.getBody(), ProblemaRegistrado.class);
            throw new NegocioException(status, problema.detail());
        }
        return new RespostaIdempotente<>(status, jsonMapper.readValue(idempotencia.getBody(), tipoResposta));
    }

    // A transação do negócio já foi desfeita; o Erro de Negócio é lembrado numa transação nova
    private void registrarErroNegocio(String idTransacao, String checksum, boolean responderHttp, NegocioException erro) {
        var idempotencia = novaIdempotencia(idTransacao, checksum);
        if (responderHttp) {
            var problema = ProblemDetail.forStatusAndDetail(erro.getStatus(), erro.getMessage());
            idempotencia.registrarResposta(erro.getStatus(), jsonMapper.writeValueAsString(problema));
        }
        try {
            idempotenciaRepository.saveAndFlush(idempotencia);
        } catch (DataIntegrityViolationException e) {
            log.warn("Erro de Negócio do Id da Transação {} já registrado por outra execução", idTransacao);
        }
    }

    private Idempotencia novaIdempotencia(String idTransacao, String checksum) {
        return Idempotencia.builder()
                .idIdempotencia(UUID.randomUUID().toString())
                .idTransacao(idTransacao)
                .checksum(checksum)
                .build();
    }

    private String checksum(Operacao operacao, Object request) {
        var conteudo = operacao.name() + ":" + jsonMapper.writeValueAsString(request);
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(conteudo.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    record ProblemaRegistrado(String detail) {
    }
}
