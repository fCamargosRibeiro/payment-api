package br.com.api.payment.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfiguration {

    @Bean
    public JsonMapper jsonMapper() {
        return JsonMapper.builder()
                .enable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                // Mesmo formato que o Spring aplica ao ProblemDetail (RFC 9457): omite vazios e expõe properties na raiz
                .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
                .build();  // Configuration locked after build()
    }
}

