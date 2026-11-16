package br.com.api.payment;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.awaitility.Awaitility.await;

/**
 * Sobe a aplicação inteira contra a mesma infraestrutura do docker-compose (Postgres com replicação lógica,
 * Kafka com Share Groups e Debezium com o conector da outbox), para testar os fluxos de ponta a ponta.
 * Os containers são iniciados uma única vez e compartilhados por todas as classes de teste.
 */
@ActiveProfiles("componenttest")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
public abstract class AbstractComponentTest {

    public static final String TOPICO_PAGAMENTO = "pagamento-v1";
    public static final String GRUPO_NOTIFICACAO = "payment-api_notificacao";

    private static final Network NETWORK = Network.newNetwork();

    // Mesmo alias, banco e credenciais do docker-compose: o conector em debezium/outbox-connector.json é usado sem alteração
    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withNetwork(NETWORK)
            .withNetworkAliases("postgres")
            .withDatabaseName("pagamentodb")
            .withUsername("myuser")
            .withPassword("secret")
            .withCommand("postgres", "-c", "wal_level=logical");

    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:4.4.0")
            .withNetwork(NETWORK)
            .withListener("kafka:19092")
            .withEnv("KAFKA_GROUP_COORDINATOR_REBALANCE_PROTOCOLS", "classic,consumer,streams,share")
            .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_REPLICATION_FACTOR", "1")
            .withEnv("KAFKA_SHARE_COORDINATOR_STATE_TOPIC_MIN_ISR", "1");

    protected static final GenericContainer<?> DEBEZIUM = new GenericContainer<>("quay.io/debezium/connect:3.3")
            .withNetwork(NETWORK)
            .withExposedPorts(8083)
            .withEnv("BOOTSTRAP_SERVERS", "kafka:19092")
            .withEnv("GROUP_ID", "payment-api-connect")
            .withEnv("CONFIG_STORAGE_TOPIC", "connect_configs")
            .withEnv("OFFSET_STORAGE_TOPIC", "connect_offsets")
            .withEnv("STATUS_STORAGE_TOPIC", "connect_statuses")
            .withEnv("CONNECT_CONFIG_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_OFFSET_STORAGE_REPLICATION_FACTOR", "1")
            .withEnv("CONNECT_STATUS_STORAGE_REPLICATION_FACTOR", "1")
            // Posição de leitura salva só a cada 10 min: uma queda abrupta do Debezium faz republicar o que já foi enviado
            .withEnv("CONNECT_OFFSET_FLUSH_INTERVAL_MS", "600000")
            .dependsOn(KAFKA, POSTGRES)
            .waitingFor(Wait.forHttp("/connectors").forPort(8083).withStartupTimeout(Duration.ofMinutes(3)));

    static {
        POSTGRES.start();
        KAFKA.start();
        DEBEZIUM.start();
        prepararKafka();
        registrarConectorOutbox();
    }

    @LocalServerPort
    private int porta;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    @Autowired
    protected JsonMapper jsonMapper;

    protected RestClient restClient;

    @DynamicPropertySource
    static void propriedades(DynamicPropertyRegistry registry) {
        // O bootstrap.yml fixa datasource e flyway em postgres:5432; aqui eles apontam para os containers
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        registry.add("spring.flyway.user", POSTGRES::getUsername);
        registry.add("spring.flyway.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @BeforeEach
    void configurarRestClient() {
        restClient = RestClient.builder()
                .baseUrl("http://localhost:" + porta)
                .defaultStatusHandler(status -> true, (request, response) -> {
                })
                .build();
    }

    protected static String bootstrapServers() {
        return KAFKA.getBootstrapServers();
    }

    // Quantas vezes o Debezium já começou a ler o WAL desde que o container foi criado (inclui reinicializações)
    protected static long inicializacoesDoDebezium() {
        return DEBEZIUM.getLogs().lines().filter(linha -> linha.contains("Processing messages")).count();
    }

    private static void prepararKafka() {
        try (var admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(TOPICO_PAGAMENTO, 1, (short) 1))).all().get();
            // Um Share Group novo só lê o que chega depois de entrar; nos testes ele lê desde o início
            var grupo = new ConfigResource(ConfigResource.Type.GROUP, GRUPO_NOTIFICACAO);
            var earliest = new AlterConfigOp(new ConfigEntry("share.auto.offset.reset", "earliest"), AlterConfigOp.OpType.SET);
            admin.incrementalAlterConfigs(Map.of(grupo, List.of(earliest))).all().get();
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao preparar o Kafka para os testes de componente", e);
        }
    }

    private static void registrarConectorOutbox() {
        var connect = "http://" + DEBEZIUM.getHost() + ":" + DEBEZIUM.getMappedPort(8083) + "/connectors/outbox-connector";
        // HTTP/1.1: o Kafka Connect não responde ao upgrade h2c que o HttpClient tenta em requisições sem corpo
        try (var http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            var config = Files.readString(Path.of("debezium/outbox-connector.json"));
            var registro = HttpRequest.newBuilder(URI.create(connect + "/config"))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(config))
                    .build();
            var resposta = http.send(registro, HttpResponse.BodyHandlers.ofString());
            if (resposta.statusCode() >= 300) {
                throw new IllegalStateException("Falha ao registrar o conector: " + resposta.body());
            }

            // Pronto quando o Debezium começa a ler o WAL: a partir daí todo insert na OUTBOX é publicado.
            // pollInSameThread: a espera roda no inicializador estático; em outra thread a lambda ficaria
            // bloqueada esperando esta classe terminar de inicializar
            try {
                await().pollInSameThread().atMost(Duration.ofMinutes(2)).pollInterval(Duration.ofSeconds(1))
                        .until(() -> DEBEZIUM.getLogs().contains("Processing messages"));
            } catch (org.awaitility.core.ConditionTimeoutException e) {
                var log = DEBEZIUM.getLogs();
                throw new IllegalStateException("O Debezium não começou a ler a OUTBOX. Log:\n"
                        + log.substring(Math.max(0, log.length() - 4000)), e);
            }
        } catch (Exception e) {
            throw new IllegalStateException("Falha ao registrar o conector da outbox no Debezium", e);
        }
    }
}
