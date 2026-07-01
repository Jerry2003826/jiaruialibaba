package com.example.agentdemo.migration;

import com.example.agentdemo.AgentBackendDemoApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the Flyway migrations produce a schema that Hibernate {@code validate} accepts on a real
 * PostgreSQL instance: the container boots, Flyway applies V1 + V2, then Hibernate validates the
 * entity mappings against the migrated schema during context startup. If validation failed the
 * context would not load and this test would fail.
 *
 * <p>Disabled automatically where Docker is unavailable (e.g. local sandboxes); intended to run in
 * CI where it provides the authoritative migrate-then-validate check for the PostgreSQL schema.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AgentBackendDemoApplication.class)
class PostgresFlywayMigrationIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.dialect", () -> "org.hibernate.dialect.PostgreSQLDialect");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.baseline-version", () -> "1");
        registry.add("demo.rag.outbox.initial-delay-ms", () -> "3600000");
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void migratesAndValidatesAgainstRealPostgres() {
        // Reaching this point means Flyway migrated and Hibernate validate passed at startup.
        Integer outboxColumns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns where table_name = 'vector_outbox_events'",
                Integer.class);
        assertThat(outboxColumns).isNotNull().isPositive();

        String indexStatusType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_name = 'rag_documents' and column_name = 'index_status'",
                String.class);
        assertThat(indexStatusType).isEqualTo("character varying");

        // The @Lob -> text fix: large text/JSON columns must be `text`, not `oid` (large objects).
        String contentType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_name = 'rag_documents' and column_name = 'content'",
                String.class);
        assertThat(contentType).isEqualTo("text");

        String payloadType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_name = 'vector_outbox_events' and column_name = 'payload_json'",
                String.class);
        assertThat(payloadType).isEqualTo("text");

        // V3 recoverable-outbox columns must exist for Hibernate validate to accept the @Version /
        // lease mapping (their absence would have failed context startup above).
        String rowVersionType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_name = 'vector_outbox_events' and column_name = 'row_version'",
                String.class);
        assertThat(rowVersionType).isEqualTo("bigint");

        Integer leaseColumns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_name = 'vector_outbox_events' and column_name = 'lease_expires_at'",
                Integer.class);
        assertThat(leaseColumns).isEqualTo(1);

        Integer demoOrderRows = jdbcTemplate.queryForObject(
                "select count(*) from demo_orders where order_id in ('20260630001', '20260630002', '20260630003')",
                Integer.class);
        assertThat(demoOrderRows).isEqualTo(3);

        Integer customerNameColumns = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.columns "
                        + "where table_name = 'demo_orders' and column_name = 'customer_name'",
                Integer.class);
        assertThat(customerNameColumns).isEqualTo(1);
    }

}
