package br.com.api.payment.notificacao;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TipoNotificacaoTest {

    @Test
    void dadoAtributosDoTemplate_quandoFormatar_deveSubstituirCadaMarcador() {
        var mensagem = TipoNotificacao.PAGAMENTO_PIX_CONCLUIDO.formatar(Map.of(
                "idPagamento", "abc",
                "valorTotal", new BigDecimal("150.00"),
                "cpfCnpjRecebedor", "98765432100"
        ));

        assertThat(mensagem).isEqualTo("Pagamento via PIX abc de 150.00 para 98765432100 concluído com sucesso");
    }

    @Test
    void dadoAtributoAusente_quandoFormatar_deveManterOMarcador() {
        var mensagem = TipoNotificacao.PAGAMENTO_TED_CONCLUIDO.formatar(Map.of("idPagamento", "abc"));

        assertThat(mensagem).isEqualTo("Pagamento via TED abc de {valorTotal} para {cpfCnpjRecebedor} concluído com sucesso");
    }

    @Test
    void dadoAtributoQueNaoEstaNoTemplate_quandoFormatar_deveIgnorar() {
        var mensagem = TipoNotificacao.PAGAMENTO_PIX_CONCLUIDO.formatar(Map.of(
                "idPagamento", "abc",
                "valorTotal", "1",
                "cpfCnpjRecebedor", "2",
                "extra", "ignorado"
        ));

        assertThat(mensagem).doesNotContain("ignorado");
    }
}
