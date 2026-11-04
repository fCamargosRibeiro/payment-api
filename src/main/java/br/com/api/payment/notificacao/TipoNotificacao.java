package br.com.api.payment.notificacao;

import java.util.Map;

enum TipoNotificacao {
    PAGAMENTO_PIX_CONCLUIDO("Pagamento via PIX {idPagamento} de {valorTotal} para {cpfCnpjRecebedor} concluído com sucesso"),
    PAGAMENTO_TED_CONCLUIDO("Pagamento via TED {idPagamento} de {valorTotal} para {cpfCnpjRecebedor} concluído com sucesso");

    private final String template;

    TipoNotificacao(String template) {
        this.template = template;
    }

    // Substitui cada {chave} do template pelo valor correspondente nos atributos
    String formatar(Map<String, Object> atributos) {
        var mensagem = template;
        for (var atributo : atributos.entrySet()) {
            mensagem = mensagem.replace("{" + atributo.getKey() + "}", String.valueOf(atributo.getValue()));
        }
        return mensagem;
    }
}
