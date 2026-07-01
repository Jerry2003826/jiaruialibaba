package com.example.agentdemo.rag;

import com.example.agentdemo.AgentBackendDemoApplication;
import com.example.agentdemo.rag.vector.VectorDocument;
import com.example.agentdemo.rag.vector.VectorStoreGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Proves the optimistic-claim guard on a real PostgreSQL instance: when many workers race to process
 * the same outbox event, exactly one wins the claim and performs the external vector-store call. A
 * non-atomic claim (the pre-fix behaviour) would let several workers process the same event, calling
 * {@code upsert} more than once.
 *
 * <p>Disabled automatically where Docker is unavailable (local sandboxes); runs in CI as the
 * authoritative concurrency check.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = AgentBackendDemoApplication.class)
class VectorOutboxConcurrencyIntegrationTest {

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

    @MockitoBean
    private VectorStoreGateway vectorStoreGateway;

    @Autowired
    private VectorOutboxWorker worker;

    @Autowired
    private VectorOutboxEventRepository outboxEventRepository;

    @Autowired
    private DocumentRepository documentRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void concurrentWorkersClaimAndProcessASingleEventExactlyOnce() throws Exception {
        DocumentEntity document = documentRepository.save(new DocumentEntity("Doc", "body to index"));
        String payload = objectMapper.writeValueAsString(List.of(
                new VectorDocument("doc-" + document.getId() + "-chunk-0", new float[] {0.1f, 0.2f}, Map.of())));
        Long eventId = outboxEventRepository.save(VectorOutboxEventEntity.upsert(document.getId(), payload)).getId();

        int workers = 16;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    worker.processCandidate(eventId);
                    return null;
                }));
            }
            startGate.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        }
        finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }

        assertThat(outboxEventRepository.findById(eventId)).get()
                .extracting(VectorOutboxEventEntity::getStatus).isEqualTo(VectorOutboxEventStatus.SUCCEEDED);
        assertThat(documentRepository.findById(document.getId())).get()
                .extracting(DocumentEntity::getIndexStatus).isEqualTo(DocumentIndexStatus.READY);
        verify(vectorStoreGateway, times(1)).upsert(anyList());
    }

}
