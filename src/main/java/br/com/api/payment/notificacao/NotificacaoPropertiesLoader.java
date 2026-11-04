package br.com.api.payment.notificacao;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

@Data
@RefreshScope
@Configuration
@ConfigurationProperties(prefix = "notificacao")
class NotificacaoPropertiesLoader {

    private Consumer consumer;

    @Data
    public static class Consumer {
        private ConsumerProperties pagamento;

        @Data
        public static class ConsumerProperties {
            private String topico;
            private String groupId;
            private Long concurrency;
        }
    }
}
