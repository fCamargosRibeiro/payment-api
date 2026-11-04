package br.com.api.payment.pagamento;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.context.annotation.Configuration;

@Data
@RefreshScope
@Configuration
@ConfigurationProperties(prefix = "pagamento")
class PagamentoPropertiesLoader {

    private Producer producer;

    @Data
    public static class Producer {
        private ProducerProperties pagamento;

        @Data
        public static class ProducerProperties {
            private String topico;
        }
    }
}
