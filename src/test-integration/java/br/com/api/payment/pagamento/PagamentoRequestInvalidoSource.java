package br.com.api.payment.pagamento;

import org.junit.jupiter.params.provider.Arguments;

import java.util.stream.Stream;

/**
 * Pedidos de pagamento que violam as constraints do PagamentoRequest, com o campo e a mensagem esperados.
 * Compartilhado pelos testes de controller de PIX e TED, que recebem o mesmo contrato.
 */
public final class PagamentoRequestInvalidoSource {

    private PagamentoRequestInvalidoSource() {
    }

    public static Stream<Arguments> pedidosInvalidos() {
        return Stream.of(
                Arguments.of("pagador ausente",
                        pedido(null, "\"98765432100\"", "10.00"), "cpfCnpjPagador", "cpfCnpjPagador é obrigatório"),
                Arguments.of("pagador em branco",
                        pedido("\"  \"", "\"98765432100\"", "10.00"), "cpfCnpjPagador", "cpfCnpjPagador é obrigatório"),
                Arguments.of("pagador com tamanho inválido",
                        pedido("\"123\"", "\"98765432100\"", "10.00"), "cpfCnpjPagador", "cpfCnpjPagador deve ter 11 (CPF) ou 14 (CNPJ) dígitos"),
                Arguments.of("pagador com máscara",
                        pedido("\"123.456.789-00\"", "\"98765432100\"", "10.00"), "cpfCnpjPagador", "cpfCnpjPagador deve ter 11 (CPF) ou 14 (CNPJ) dígitos"),
                Arguments.of("recebedor ausente",
                        pedido("\"12345678900\"", null, "10.00"), "cpfCnpjRecebedor", "cpfCnpjRecebedor é obrigatório"),
                Arguments.of("recebedor com 13 dígitos",
                        pedido("\"12345678900\"", "\"1234567890123\"", "10.00"), "cpfCnpjRecebedor", "cpfCnpjRecebedor deve ter 11 (CPF) ou 14 (CNPJ) dígitos"),
                Arguments.of("valor ausente",
                        pedido("\"12345678900\"", "\"98765432100\"", null), "valorTotal", "valorTotal é obrigatório"),
                Arguments.of("valor zero",
                        pedido("\"12345678900\"", "\"98765432100\"", "0"), "valorTotal", "valorTotal deve ser maior que zero"),
                Arguments.of("valor negativo",
                        pedido("\"12345678900\"", "\"98765432100\"", "-5.00"), "valorTotal", "valorTotal deve ser maior que zero"),
                Arguments.of("valor com 3 casas decimais",
                        pedido("\"12345678900\"", "\"98765432100\"", "10.123"), "valorTotal", "valorTotal deve ter no máximo 16 dígitos inteiros e 2 casas decimais")
        );
    }

    private static String pedido(String cpfCnpjPagador, String cpfCnpjRecebedor, String valorTotal) {
        return """
                {"cpfCnpjPagador":%s,"cpfCnpjRecebedor":%s,"valorTotal":%s}""".formatted(cpfCnpjPagador, cpfCnpjRecebedor, valorTotal);
    }
}
