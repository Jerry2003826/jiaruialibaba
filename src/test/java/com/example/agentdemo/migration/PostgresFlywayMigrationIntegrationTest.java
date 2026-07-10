package com.example.agentdemo.migration;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.common.PublicIdGenerator;
import com.example.agentdemo.knowledge.Citation;
import com.example.agentdemo.knowledge.KnowledgeBaseEntity;
import com.example.agentdemo.knowledge.KnowledgeBaseRepository;
import com.example.agentdemo.knowledge.KnowledgeIngestionService;
import com.example.agentdemo.knowledge.KnowledgeSearchService;
import com.example.agentdemo.rag.DocumentManagementService;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.workflow.governance.WorkflowBuilderKnowledgeService;
import com.example.agentdemo.workflow.governance.WorkflowGovernanceRule;
import com.example.agentdemo.workflow.governance.WorkflowRuleCatalog;
import com.example.agentdemo.workflow.governance.WorkflowRulePack;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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

    @Autowired
    private WorkflowRuleCatalog workflowRuleCatalog;

    @Autowired
    private KnowledgeBaseRepository knowledgeBaseRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private KnowledgeIngestionService knowledgeIngestionService;

    @Autowired
    private DocumentManagementService documentManagementService;

    @Autowired
    private KnowledgeSearchService knowledgeSearchService;

    @Autowired
    private PublicIdGenerator publicIdGenerator;

    @Autowired
    private WorkflowBuilderKnowledgeService workflowBuilderKnowledgeService;

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

        String kbPurposeType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_name = 'knowledge_bases' and column_name = 'purpose'",
                String.class);
        assertThat(kbPurposeType).isEqualTo("character varying");

        String systemManagedType = jdbcTemplate.queryForObject(
                "select data_type from information_schema.columns "
                        + "where table_name = 'knowledge_bases' and column_name = 'system_managed'",
                String.class);
        assertThat(systemManagedType).isEqualTo("boolean");

        jdbcTemplate.update(
                "insert into knowledge_bases (kb_id, owner_id, name, created_at, updated_at) values (?, ?, ?, now(), now())",
                "kb-business-defaults", "owner-business", "Business KB");
        String defaultPurpose = jdbcTemplate.queryForObject(
                "select purpose from knowledge_bases where kb_id = 'kb-business-defaults'",
                String.class);
        Boolean defaultSystemManaged = jdbcTemplate.queryForObject(
                "select system_managed from knowledge_bases where kb_id = 'kb-business-defaults'",
                Boolean.class);
        assertThat(defaultPurpose).isEqualTo("BUSINESS");
        assertThat(defaultSystemManaged).isFalse();

        jdbcTemplate.update(
                "insert into knowledge_bases (kb_id, owner_id, name, purpose, system_managed, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, now(), now())",
                "kb-builder-1", "owner-builder", "Builder KB", "WORKFLOW_BUILDER", true);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into knowledge_bases (kb_id, owner_id, name, purpose, system_managed, created_at, updated_at) "
                        + "values (?, ?, ?, ?, ?, now(), now())",
                "kb-builder-2", "owner-builder", "Builder KB 2", "WORKFLOW_BUILDER", true))
                .hasMessageContaining("uq_knowledge_bases_owner_workflow_builder_managed");

        jdbcTemplate.update(
                "insert into rag_documents (owner_id, title, content, created_at, index_status, kb_id, source_type) "
                        + "values (?, ?, ?, now(), ?, ?, ?)",
                "owner-builder", "Workflow Builder Guidance: core/core-registered-node-types",
                "content", "READY", "kb-builder-1", "BUILDER");
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into rag_documents (owner_id, title, content, created_at, index_status, kb_id, source_type) "
                        + "values (?, ?, ?, now(), ?, ?, ?)",
                "owner-builder", "Workflow Builder Guidance: core/core-registered-node-types",
                "duplicate", "PENDING", "kb-builder-1", "BUILDER"))
                .hasMessageContaining("uq_rag_documents_builder_identity_active");
    }

    @Test
    void concurrentBuilderInstancesRecoverAfterRealUniqueConstraintLoss() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ownerId = "owner-concurrent-" + suffix;
        String kbId = "kb-concurrent-" + suffix;
        insertManagedKnowledgeBase(kbId, ownerId);

        CyclicBarrier ingestionBarrier = new CyclicBarrier(2);
        KnowledgeIngestionService barrierIngestion = mock(KnowledgeIngestionService.class);
        doAnswer(invocation -> {
            ingestionBarrier.await(30, TimeUnit.SECONDS);
            return knowledgeIngestionService.addManagedTextDocument(
                    invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
        }).when(barrierIngestion).addManagedTextDocument(anyString(), anyString(), anyString());

        WorkflowBuilderKnowledgeService first = builderService(barrierIngestion);
        WorkflowBuilderKnowledgeService second = builderService(barrierIngestion);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<Citation>> firstResult = executor.submit(
                    () -> runAs(ownerId,
                            () -> first.retrieve("customer-service-ecommerce", "refund order", 6)));
            Future<List<Citation>> secondResult = executor.submit(
                    () -> runAs(ownerId,
                            () -> second.retrieve("customer-service-ecommerce", "refund order", 6)));

            assertThat(firstResult.get(90, TimeUnit.SECONDS)).isNotEmpty();
            assertThat(secondResult.get(90, TimeUnit.SECONDS)).isNotEmpty();
        }

        int expectedRuleCount = workflowRuleCatalog.allPacks().stream()
                .mapToInt(pack -> pack.rules().size())
                .sum();
        Integer activeDocuments = jdbcTemplate.queryForObject(
                "select count(*) from rag_documents where owner_id = ? and kb_id = ? and source_type = 'BUILDER' "
                        + "and index_status not in ('DELETING', 'DELETED')",
                Integer.class, ownerId, kbId);
        Integer duplicateIdentities = jdbcTemplate.queryForObject(
                "select count(*) from (select title from rag_documents where owner_id = ? and kb_id = ? "
                        + "and source_type = 'BUILDER' and index_status not in ('DELETING', 'DELETED') "
                        + "group by title having count(*) > 1) duplicates",
                Integer.class, ownerId, kbId);
        assertThat(activeDocuments).isEqualTo(expectedRuleCount);
        assertThat(duplicateIdentities).isZero();
    }

    @Test
    void concurrentBuilderInstancesRecoverWhenCreatingTheManagedKnowledgeBase() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ownerId = "owner-kb-race-" + suffix;
        CyclicBarrier creationBarrier = new CyclicBarrier(2);
        KnowledgeBaseRepository barrierRepository = mock(KnowledgeBaseRepository.class);
        when(barrierRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                ownerId, com.example.agentdemo.knowledge.KnowledgeBasePurpose.WORKFLOW_BUILDER))
                .thenAnswer(invocation -> knowledgeBaseRepository.findByOwnerIdAndPurposeAndSystemManagedTrue(
                        ownerId, com.example.agentdemo.knowledge.KnowledgeBasePurpose.WORKFLOW_BUILDER));
        doAnswer(invocation -> {
            creationBarrier.await(30, TimeUnit.SECONDS);
            return knowledgeBaseRepository.saveAndFlush(invocation.getArgument(0));
        }).when(barrierRepository).saveAndFlush(any(KnowledgeBaseEntity.class));

        WorkflowBuilderKnowledgeService first = builderService(barrierRepository, knowledgeIngestionService);
        WorkflowBuilderKnowledgeService second = builderService(barrierRepository, knowledgeIngestionService);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<List<Citation>> firstResult = executor.submit(
                    () -> runAs(ownerId,
                            () -> first.retrieve("customer-service-ecommerce", "refund order", 6)));
            Future<List<Citation>> secondResult = executor.submit(
                    () -> runAs(ownerId,
                            () -> second.retrieve("customer-service-ecommerce", "refund order", 6)));

            assertThat(firstResult.get(90, TimeUnit.SECONDS)).isNotEmpty();
            assertThat(secondResult.get(90, TimeUnit.SECONDS)).isNotEmpty();
        }

        Integer managedKnowledgeBases = jdbcTemplate.queryForObject(
                "select count(*) from knowledge_bases where owner_id = ? and purpose = 'WORKFLOW_BUILDER' "
                        + "and system_managed = true",
                Integer.class, ownerId);
        assertThat(managedKnowledgeBases).isEqualTo(1);
    }

    @Test
    void synchronizationReplacesChangedGuidanceAndDeletesObsoleteGuidanceOnPostgres() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String ownerId = "owner-stale-" + suffix;
        String kbId = "kb-stale-" + suffix;
        insertManagedKnowledgeBase(kbId, ownerId);
        WorkflowRulePack pack = workflowRuleCatalog.allPacks().getFirst();
        WorkflowGovernanceRule rule = pack.rules().getFirst();
        String currentTitle = "Workflow Builder Guidance: " + pack.id() + "/" + rule.id();
        String obsoleteTitle = "Workflow Builder Guidance: removed/obsolete-rule";
        insertBuilderDocument(ownerId, kbId, currentTitle, "outdated guidance", "stale-hash");
        insertBuilderDocument(ownerId, kbId, obsoleteTitle, "obsolete guidance", "obsolete-hash");

        List<Citation> citations = runAs(ownerId,
                () -> workflowBuilderKnowledgeService.retrieve("customer-service-ecommerce", "registered refund", 6));

        assertThat(citations).isNotEmpty();
        String refreshedHash = jdbcTemplate.queryForObject(
                "select content_hash from rag_documents where owner_id = ? and kb_id = ? and title = ? "
                        + "and source_type = 'BUILDER' and index_status not in ('DELETING', 'DELETED')",
                String.class, ownerId, kbId, currentTitle);
        Integer obsoleteDocuments = jdbcTemplate.queryForObject(
                "select count(*) from rag_documents where owner_id = ? and kb_id = ? and title = ? "
                        + "and source_type = 'BUILDER' and index_status not in ('DELETING', 'DELETED')",
                Integer.class, ownerId, kbId, obsoleteTitle);
        assertThat(refreshedHash).isNotEqualTo("stale-hash").hasSize(64);
        assertThat(obsoleteDocuments).isZero();
    }

    private WorkflowBuilderKnowledgeService builderService(KnowledgeIngestionService ingestionService) {
        return builderService(knowledgeBaseRepository, ingestionService);
    }

    private WorkflowBuilderKnowledgeService builderService(KnowledgeBaseRepository repository,
            KnowledgeIngestionService ingestionService) {
        return new WorkflowBuilderKnowledgeService(
                workflowRuleCatalog,
                repository,
                documentRepository,
                ingestionService,
                documentManagementService,
                knowledgeSearchService,
                publicIdGenerator);
    }

    private void insertManagedKnowledgeBase(String kbId, String ownerId) {
        jdbcTemplate.update(
                "insert into knowledge_bases (kb_id, owner_id, name, purpose, system_managed, created_at, updated_at) "
                        + "values (?, ?, ?, 'WORKFLOW_BUILDER', true, now(), now())",
                kbId, ownerId, "Builder KB");
    }

    private void insertBuilderDocument(String ownerId, String kbId, String title, String content, String contentHash) {
        jdbcTemplate.update(
                "insert into rag_documents (owner_id, title, content, created_at, index_status, kb_id, source_type, "
                        + "content_hash) values (?, ?, ?, now(), 'READY', ?, 'BUILDER', ?)",
                ownerId, title, content, kbId, contentHash);
    }

    private <T> T runAs(String ownerId, Callable<T> action) throws Exception {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(ownerId, "n/a", List.of()));
        SecurityContextHolder.setContext(context);
        try {
            return action.call();
        }
        finally {
            SecurityContextHolder.clearContext();
        }
    }

}
