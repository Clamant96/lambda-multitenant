package com.a5solutions.lambdamultitenant.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.Database;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClientBuilder;
import software.amazon.awssdk.services.secretsmanager.model.GetSecretValueRequest;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Multitenant dinamico: a Lambda nao tem banco fixo.
 * Cada requisicao informa o secret (X-Secret-Id); o secret aponta para o banco do tenant.
 *
 *   header X-Secret-Id -> SecretContextHolder -> TenantRoutingDataSource
 *       -> (1a vez) Secrets Manager -> pool Hikari em cache -> (proximas vezes) cache
 */
@Configuration
public class DatabaseConfig {

    private static final Logger log = LoggerFactory.getLogger(DatabaseConfig.class);

    // Chave do H2 no mapa de bancos do TenantRoutingDataSource (os tenants usam o proprio secretId).
    // Usada so no startup do Spring, quando ainda nao existe requisicao/tenant.
    private static final String H2_BOOTSTRAP_KEY = "h2-bootstrap";

    @Value("${aws.region}")
    private String region;

    @Value("${aws.endpoint-url:}")
    private String endpointUrl;

    private SecretsManagerClient secretsManagerClient;

    @Bean
    @Primary
    public TenantRoutingDataSource dataSource() {
        return new TenantRoutingDataSource(
                createH2BootstrapDataSource(),
                this::createRdsDataSourceFromSecret,
                this::applySchemaUpdate
        );
    }

    /**
     * DataSource que escolhe o banco a cada getConnection(), com base no tenant da requisicao.
     */
    public static class TenantRoutingDataSource extends AbstractRoutingDataSource {

        private final DataSource h2BootstrapDataSource;
        private final Function<String, DataSource> dataSourceFactory;
        private final Consumer<DataSource> schemaEnsurer;
        private final Map<String, DataSource> dataSourceCache = new ConcurrentHashMap<>();

        public TenantRoutingDataSource(DataSource h2BootstrapDataSource,
                                       Function<String, DataSource> dataSourceFactory,
                                       Consumer<DataSource> schemaEnsurer) {
            this.h2BootstrapDataSource = h2BootstrapDataSource;
            this.dataSourceFactory = dataSourceFactory;
            this.schemaEnsurer = schemaEnsurer;

            setDefaultTargetDataSource(h2BootstrapDataSource);
            setLenientFallback(false);
            atualizarTargets();

            // Container Lambda "quente" reaproveita os pools; so fechamos quando ele morre.
            Runtime.getRuntime().addShutdownHook(new Thread(() ->
                    dataSourceCache.values().forEach(ds -> {
                        if (ds instanceof HikariDataSource hds && !hds.isClosed()) {
                            hds.close();
                        }
                    })));
        }

        @Override
        protected Object determineCurrentLookupKey() {
            String secretId = SecretContextHolder.getSecretId();

            // So acontece no startup do Spring (fora de requisicao). Requisicoes sem header
            // sao barradas antes, com 400, pelo SecretHeaderFilter.
            if (secretId == null || secretId.isBlank()) {
                return H2_BOOTSTRAP_KEY;
            }

            registrarTenant(secretId);
            return secretId;
        }

        /**
         * Cria (uma unica vez por container) o pool do tenant. Chamadas seguintes vem do cache.
         *
         * @return true se o pool foi criado agora; false se ja estava em cache
         */
        public boolean registrarTenant(String secretId) {
            if (dataSourceCache.containsKey(secretId)) {
                return false;
            }
            synchronized (this) {
                if (dataSourceCache.containsKey(secretId)) {
                    return false;
                }
                DataSource novo = dataSourceFactory.apply(secretId);
                schemaEnsurer.accept(novo);
                dataSourceCache.put(secretId, novo);
                atualizarTargets();
                return true;
            }
        }

        public int totalTenantsEmCache() {
            return dataSourceCache.size();
        }

        private void atualizarTargets() {
            Map<Object, Object> targets = new HashMap<>(dataSourceCache);
            targets.put(H2_BOOTSTRAP_KEY, h2BootstrapDataSource);
            setTargetDataSources(targets);
            afterPropertiesSet();
        }
    }

    private DataSource createRdsDataSourceFromSecret(String secretId) {
        SecretConfig secretConfig = loadSecret(secretId);
        HikariDataSource dataSource = createRdsDataSource(secretId, secretConfig);
        try {
            testConnection(dataSource);
        } catch (RuntimeException e) {
            dataSource.close();
            throw new TenantInvalidoException(
                    "Nao foi possivel conectar no banco do tenant " + secretId + ": " + e.getMessage(), e);
        }
        return dataSource;
    }

    private HikariDataSource createRdsDataSource(String secretId, SecretConfig config) {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("tenant-" + secretId.replaceAll("[^a-zA-Z0-9-]", "-"));
        hikariConfig.setJdbcUrl(config.jdbcUrl);
        hikariConfig.setUsername(config.username);
        hikariConfig.setPassword(config.password);
        hikariConfig.setDriverClassName("org.postgresql.Driver");

        // Padrao do agilcorp para Lambda: pool pequeno e sem conexao presa por container.
        hikariConfig.setMaximumPoolSize(2);
        hikariConfig.setMinimumIdle(0);
        hikariConfig.setConnectionTimeout(10000);
        hikariConfig.setIdleTimeout(30000);
        hikariConfig.setMaxLifetime(900000);
        hikariConfig.setLeakDetectionThreshold(60000);

        hikariConfig.addDataSourceProperty("socketTimeout", "30");
        hikariConfig.addDataSourceProperty("connectTimeout", "10");
        hikariConfig.setConnectionTestQuery("SELECT 1");

        return new HikariDataSource(hikariConfig);
    }

    /**
     * Banco em memoria usado apenas para o Spring/JPA subirem antes de existir qualquer tenant.
     * Nunca atende requisicao de negocio.
     */
    private DataSource createH2BootstrapDataSource() {
        HikariConfig config = new HikariConfig();
        config.setPoolName(H2_BOOTSTRAP_KEY);
        config.setJdbcUrl("jdbc:h2:mem:bootstrap;MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
        config.setUsername("sa");
        config.setPassword("");
        config.setDriverClassName("org.h2.Driver");
        config.setMaximumPoolSize(1);
        config.setMinimumIdle(0);
        return new HikariDataSource(config);
    }

    /**
     * Novo tenant = novo secret. As tabelas sao criadas automaticamente no 1o acesso,
     * sem deploy e sem script manual.
     */
    private void applySchemaUpdate(DataSource dataSource) {
        if (schemaJaInicializado(dataSource)) {
            return;
        }
        try {
            LocalContainerEntityManagerFactoryBean emfBean = new LocalContainerEntityManagerFactoryBean();
            emfBean.setDataSource(dataSource);

            HibernateJpaVendorAdapter vendorAdapter = new HibernateJpaVendorAdapter();
            vendorAdapter.setDatabase(Database.POSTGRESQL);
            vendorAdapter.setGenerateDdl(true);
            emfBean.setJpaVendorAdapter(vendorAdapter);
            emfBean.setPackagesToScan("com.a5solutions.lambdamultitenant.model");

            Properties jpaProps = new Properties();
            jpaProps.setProperty("hibernate.hbm2ddl.auto", "update");
            jpaProps.setProperty("hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
            emfBean.setJpaProperties(jpaProps);

            emfBean.afterPropertiesSet();
            emfBean.destroy();
            marcarSchemaInicializado(dataSource);
            log.info("schema criado/atualizado no banco do tenant");
        } catch (Exception e) {
            log.warn("Schema update falhou: {}", e.getMessage());
        }
    }

    private boolean schemaJaInicializado(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.prepareStatement(
                     "SELECT to_regclass(current_schema() || '.schema_version') IS NOT NULL");
             var rs = stmt.executeQuery()) {
            return rs.next() && rs.getBoolean(1);
        } catch (Exception e) {
            return false;
        }
    }

    private void marcarSchemaInicializado(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS schema_version (initialized_at TIMESTAMP DEFAULT now())");
        } catch (Exception e) {
            log.warn("Falha ao criar schema_version: {}", e.getMessage());
        }
    }

    private void testConnection(DataSource dataSource) {
        try (Connection conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT 1")) {
            rs.next();
        } catch (Exception e) {
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    /**
     * Le o JSON do secret: { host, port, dbname, username, password, schema? }
     */
    private SecretConfig loadSecret(String secretId) {
        String secretString;
        try {
            secretString = secretsManagerClient().getSecretValue(
                    GetSecretValueRequest.builder().secretId(secretId).build()
            ).secretString();
        } catch (Exception e) {
            throw new TenantInvalidoException("Secret " + secretId + " nao encontrado no Secrets Manager", e);
        }

        try {
            JsonNode json = new ObjectMapper().readTree(secretString);

            String host = getJsonValue(json, "host", "address", "endpoint");
            int port = json.has("port") ? json.get("port").asInt() : 5432;
            String username = getJsonValue(json, "username", "user");
            String password = getJsonValue(json, "password", "pass");
            String dbname = getJsonValue(json, "dbname", "database", "db");
            String schema = getJsonValue(json, "schema");

            if (host == null || username == null || password == null || dbname == null) {
                throw new IllegalArgumentException("campos obrigatorios ausentes (host, username, password, dbname)");
            }

            SecretConfig config = new SecretConfig();
            config.jdbcUrl = schema == null
                    ? String.format("jdbc:postgresql://%s:%d/%s", host, port, dbname)
                    : String.format("jdbc:postgresql://%s:%d/%s?currentSchema=%s", host, port, dbname, schema);
            config.username = username;
            config.password = password;
            return config;

        } catch (Exception e) {
            throw new TenantInvalidoException("Secret " + secretId + " com formato invalido: " + e.getMessage(), e);
        }
    }

    // Um client por container (e nao um por secret): evita recriar conexao HTTP a cada tenant novo.
    private synchronized SecretsManagerClient secretsManagerClient() {
        if (secretsManagerClient == null) {
            SecretsManagerClientBuilder builder = SecretsManagerClient.builder().region(Region.of(region));

            // Local: aponta para o LocalStack. Na AWS a variavel nao existe e o client usa o endpoint real.
            if (endpointUrl != null && !endpointUrl.isBlank()) {
                builder.endpointOverride(URI.create(endpointUrl))
                        .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")));
                log.info("Secrets Manager apontando para {}", endpointUrl);
            }
            secretsManagerClient = builder.build();
        }
        return secretsManagerClient;
    }

    private String getJsonValue(JsonNode json, String... keys) {
        for (String key : keys) {
            if (json.has(key) && !json.get(key).isNull()) {
                return json.get(key).asText();
            }
        }
        return null;
    }

    private static class SecretConfig {
        String jdbcUrl;
        String username;
        String password;
    }
}
