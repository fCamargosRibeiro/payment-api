# payment-api

[![Java](https://img.shields.io/badge/Java-25-orange.svg)](https://openjdk.org/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Apache Kafka](https://img.shields.io/badge/Apache%20Kafka-4.4.0%20(KIP--932)-black.svg)](https://kafka.apache.org/)
[![Debezium](https://img.shields.io/badge/Debezium-3.3-red.svg)](https://debezium.io/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-latest-blue.svg)](https://www.postgresql.org/)

## Sobre a PoC

Esta prova de conceito mostra como receber pagamentos (PIX e TED) de forma **idempotente** e publicar eventos no Kafka com **garantia transacional**. Ela combina três técnicas:

- **Idempotência por Id da Transação:** repetir um pedido devolve a mesma resposta sem pagar duas vezes. Reaproveitar o mesmo id com outro conteúdo é recusado com `409`.
- **Outbox pattern com Debezium (CDC):** o evento `PAGAMENTO_CONCLUIDO` é gravado no banco na mesma transação do pagamento, e o Debezium o publica no Kafka lendo o WAL do Postgres. Assim não existe pagamento sem evento, nem evento sem pagamento.
- **Um tópico de pagamento, vários consumidores (KIP-932):** o tópico `pagamento-v1` é o contrato do domínio de pagamento. Cada feature que reage a pagamentos o consome com seu próprio Share Group e sua própria idempotência. Hoje é só a notificação, mas uma contabilidade, por exemplo, entraria só como mais um consumidor.

```
POST /pix/pagamentos ─┐                          ┌─► PAGAMENTO
POST /ted/pagamentos ─┴─► IdempotenciaComponent ─┼─► IDEMPOTENCIA (checksum + resposta)
   header X-Id-Transacao       (1 transação)     └─► OUTBOX (evento PAGAMENTO_CONCLUIDO)
                                                          │
                                    Debezium lê o WAL      │ Outbox Event Router
                                                          ▼
                                                    pagamento-v1
                                                          │
                          ┌───────────────────────────────┴──────────────────────────┐
     NotificacaoConsumer (grupo _notificacao)                 (futuro) ContabilidadeConsumer (grupo _contabilidade)
                          │
     IdempotenciaComponent ─► simula a notificação
```

---

## Pré-requisitos

- Docker com Docker Compose
- JDK 25. Sem JDK local, veja [Executar sem JDK local](#executar-sem-jdk-local).

---

## Como executar

### 1. Subir a infraestrutura

```bash
docker compose up -d
```

| Serviço | Porta | Função |
| :--- | :--- | :--- |
| `postgres` | `5432` | Banco `pagamentodb` (usuário `myuser`, senha `secret`), com `wal_level=logical` para o CDC |
| `kafka` | `9092` (rede Docker), `29092` (host) | Broker Kafka 4.4.0 em KRaft, com Share Groups habilitados |
| `kafka-init-topics` | — | Cria o tópico `pagamento-v1` e termina |
| `connect` | `8083` | Kafka Connect com Debezium 3.3 |
| `connect-init` | — | Registra o conector [debezium/outbox-connector.json](debezium/outbox-connector.json) e termina |

Confira se o conector está rodando. Ele leva alguns segundos para subir:

```bash
curl -s http://localhost:8083/connectors/outbox-connector/status
```

A resposta deve trazer `"state":"RUNNING"` para o conector e para a task.

### 2. Iniciar a aplicação

```bash
./gradlew bootRun --args='--spring.profiles.active=local'
```

O perfil `local` ([bootstrap-local.yml](src/main/resources/bootstrap-local.yml)) aponta para o Postgres em `localhost:5432` e para o Kafka em `localhost:29092`. Na subida, o Flyway cria as tabelas `PAGAMENTO`, `IDEMPOTENCIA` e `OUTBOX`. A aplicação responde em `http://localhost:8080`, e a documentação da API (Swagger) fica em [http://localhost:8080/swagger-ui.html](http://localhost:8080/swagger-ui.html). A especificação OpenAPI em JSON fica em `/v3/api-docs`.

### 3. Fazer um pagamento

```bash
curl -i -X POST http://localhost:8080/pix/pagamentos -H 'Content-Type: application/json' -H 'X-Id-Transacao: tx-123' -d '{"cpfCnpjPagador":"12345678900","cpfCnpjRecebedor":"98765432100","valorTotal":150.00}'
```

Resposta `201`:

```json
{ "idPagamento" : "dbd76478-f941-492c-a397-972cbd5c7d9e", "valorTotal" : 150.00 }
```

O TED funciona igual, em `/ted/pagamentos`. O log da aplicação mostra a notificação simulada:

```
Notificando usuário [PAGAMENTO_PIX_CONCLUIDO]: Pagamento via PIX dbd76478-... de 150.0 para 98765432100 concluído com sucesso
```

---

## Comportamento da idempotência

| Cenário | Como reproduzir | Resultado |
| :--- | :--- | :--- |
| Reexecução | Repetir o mesmo comando | `201` com o mesmo `idPagamento`, sem novo pagamento nem nova notificação |
| Conflito de Idempotência | Mesmo `X-Id-Transacao` com outro `valorTotal`, ou no `/ted` | `409` |
| Erro de Negócio | `valorTotal` menor ou igual a zero, ou pagador igual ao recebedor | `422`, repetido igual nas Reexecuções |
| Sem Id da Transação | Omitir o header `X-Id-Transacao` | `400` |

Os erros seguem o formato `ProblemDetail` (RFC 9457):

```json
{ "detail" : "Id da Transação tx-123 já foi processado com outro conteúdo", "instance" : "/pix/pagamentos", "status" : 409, "title" : "Conflict" }
```

Se duas requisições com o mesmo `X-Id-Transacao` chegarem ao mesmo tempo, a segunda espera a primeira terminar e responde como Reexecução.

Cada linha da outbox guarda o Id da Transação que a gerou, e o Debezium o envia no header `X-Id-Transacao`. Nos consumidores do `pagamento-v1`, a idempotência é registrada como `<Id da Transação>:<OPERACAO>`, por exemplo `tx-123:NOTIFICACAO`. Assim cada feature que consome o mesmo evento tem o próprio controle, e no banco dá para ligar o pedido original às reações dele com `where id_transacao like 'tx-123%'`. Uma mensagem repetida é confirmada (`acknowledge`) sem simular de novo. Um conflito ou a falta do header rejeita a mensagem (`reject`).

---

## Como funciona

| Pacote | Responsabilidade |
| :--- | :--- |
| `pix`, `ted` | Endpoints REST. O service define o tipo de pagamento e delega o cadastro ao `pagamento` |
| `pagamento` | Regras de negócio, persistência e o evento `PAGAMENTO_CONCLUIDO` do tópico `pagamento-v1` (contrato em `PagamentoEvento`) |
| `idempotencia` | Dona da transação: grava a idempotência, os eventos na outbox e a resposta. Trata a Reexecução e o Conflito |
| `outbox` | Grava as linhas da tabela `OUTBOX`; só a idempotência usa |
| `notificacao` | Consome o `pagamento-v1` com o grupo `_notificacao` e simula o aviso ao pagador |

**Fluxo de um pagamento:** o controller chama `IdempotenciaComponent.executar(...)`, passando o processamento do PIX ou do TED. Numa única transação, a idempotência:

1. grava a linha da `IDEMPOTENCIA`, com o Id da Transação e o checksum (SHA-256 da operação e do pedido);
2. executa o processamento, que grava o `PAGAMENTO` e devolve a resposta e o evento `PAGAMENTO_CONCLUIDO`;
3. grava cada evento na `OUTBOX`;
4. guarda a resposta HTTP na própria `IDEMPOTENCIA`.

Se qualquer passo falhar, tudo é desfeito. Depois do commit, o Debezium publica cada linha nova da `OUTBOX` no tópico indicado em `TXT_TOPICO`.

---

## Inspecionar o que aconteceu

Tabelas:

```bash
docker compose exec postgres psql -U myuser -d pagamentodb -c "select id_transacao, num_status_http from idempotencia" -c "select id_outbox, txt_topico from outbox"
```

Mensagens publicadas pelo Debezium, com os headers:

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic pagamento-v1 --from-beginning --timeout-ms 5000 --formatter-property print.headers=true
```

---

## Executar sem JDK local

O build e a aplicação também rodam em containers com JDK 25, na rede do Compose. Nesse caso, a aplicação usa a configuração padrão, que aponta para `postgres:5432` e `kafka:9092`.

Gerar o jar:

```bash
docker run --rm -v "$PWD":/app -v payment-api-gradle-cache:/root/.gradle -w /app eclipse-temurin:25-jdk ./gradlew bootJar -x test
```

Subir a aplicação:

```bash
docker run -d --name payment-api-app --network pagamento-network -p 8080:8080 -v "$PWD/build/libs":/app eclipse-temurin:25-jdk java -jar /app/payment-api-0.0.1-SNAPSHOT.jar
```

Acompanhar o log:

```bash
docker logs -f payment-api-app
```

---

## Encerrar e limpar

Parar a aplicação, se ela estiver rodando em container:

```bash
docker rm -f payment-api-app
```

Derrubar a infraestrutura e apagar os dados do banco, inclusive o slot de replicação do Debezium:

```bash
docker compose down -v
```

> Se o conector ficar parado com o Postgres no ar, o slot de replicação segura o WAL e o disco do banco cresce até o conector voltar.
