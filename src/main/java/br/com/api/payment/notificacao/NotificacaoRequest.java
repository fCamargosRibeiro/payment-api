package br.com.api.payment.notificacao;

import java.util.Map;

record NotificacaoRequest(String destinatario,
                          TipoNotificacao tipoNotificacao,
                          Map<String, Object> atributos) {
}
