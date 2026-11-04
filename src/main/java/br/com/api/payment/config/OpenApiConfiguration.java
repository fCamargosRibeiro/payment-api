package br.com.api.payment.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class OpenApiConfiguration {

    @Bean
    OpenAPI openApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("payment-api")
                        .version("v1")
                        .description("""
                                Pagamentos PIX e TED idempotentes.

                                Toda requisição exige o header `X-Id-Transacao`: repetir o mesmo pedido \
                                com o mesmo id devolve a resposta original; usar o mesmo id com outro \
                                conteúdo devolve `409`. Erros de negócio devolvem `422`."""));
    }
}
