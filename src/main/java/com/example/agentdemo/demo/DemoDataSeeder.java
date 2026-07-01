package com.example.agentdemo.demo;

import com.example.agentdemo.order.DemoOrderEntity;
import com.example.agentdemo.order.DemoOrderRepository;
import com.example.agentdemo.rag.DocumentIndexStatus;
import com.example.agentdemo.rag.DocumentRepository;
import com.example.agentdemo.rag.RagService;
import com.example.agentdemo.rag.dto.DocumentRequest;
import com.example.agentdemo.security.SecurityIdentity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "demo.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);
    private static final List<KnowledgeDocumentSeed> KNOWLEDGE_DOCUMENT_SEEDS = List.of(
            new KnowledgeDocumentSeed("已发货订单退货流程", """
                    已发货订单退货流程。

                    适用场景：客户询问已发货订单、运输中订单、包裹派送中的通用退货政策或退货流程。

                    标准处理方式：
                    1. 如果包裹还在运输途中，系统通常无法直接拦截物流。
                    2. 客户可以在快递派送时向快递员说明拒收，包裹退回仓库后进入退款处理。
                    3. 如果客户已经签收，可以保持商品完好，签收后提交退货退款申请。
                    4. 仓库收到退回商品并验收无误后，再按原支付路径安排退款。
                    5. 如果客户提供了具体订单号，再查询订单工具确认该订单的状态、承运商和下一步建议。

                    回复要求：通用流程问题优先基于本知识库回答；不要虚构具体订单状态、运单号或退款时间。
                    """),
            new KnowledgeDocumentSeed("订单号收集与物流查询规则", """
                    订单号收集与物流查询规则。

                    当客户询问“我的订单”“帮我查物流”“我的退款到哪了”等具体订单问题时，必须先拿到有效完整订单号。
                    Demo 环境中的有效订单号为至少 8 位数字。客户只提供 abc123、口头描述、姓名或模糊信息时，不要查询工具，
                    应礼貌说明需要完整订单号。

                    只有在客户提供具体订单号，或上文明确正在处理某个具体订单时，才可以调用订单查询工具。
                    """));
    private static final List<DemoOrderSeed> ORDER_SEEDS = List.of(
            new DemoOrderSeed(
                    "20260630001",
                    "Alice Chen",
                    "SHIPPED",
                    true,
                    new BigDecimal("299.00"),
                    "CNY",
                    "SF Express",
                    "SF20260630001",
                    LocalDate.of(2026, 7, 2),
                    "Package left the Shanghai transit center",
                    "Tell the customer the order has shipped and provide the tracking number.",
                    "If the customer asks to return this shipped order, explain that direct system interception is unavailable while the parcel is in transit. The customer can reject delivery when the courier arrives, or receive the parcel and submit a return-refund request from the order page."),
            new DemoOrderSeed(
                    "20260630002",
                    "Bo Li",
                    "PENDING_RETURN",
                    true,
                    new BigDecimal("159.00"),
                    "CNY",
                    "JD Logistics",
                    "JD20260630002",
                    LocalDate.of(2026, 7, 3),
                    "Return request submitted, awaiting warehouse review",
                    "Tell the customer the return request is under review and provide the current tracking number.",
                    "If the customer asks about the return, explain that the request has been received and is waiting for warehouse review. Do not promise an immediate refund until review completes."),
            new DemoOrderSeed(
                    "20260630003",
                    "Mina Zhang",
                    "PROCESSING",
                    false,
                    new BigDecimal("89.00"),
                    "CNY",
                    null,
                    null,
                    null,
                    "Order created, waiting for payment confirmation",
                    "Ask the customer to complete payment before shipment can be arranged.",
                    "If the customer asks about shipment, explain that shipment cannot be arranged before payment confirmation."));

    private final DemoOrderRepository demoOrderRepository;
    private final DocumentRepository documentRepository;
    private final RagService ragService;
    private final TransactionTemplate transactionTemplate;

    public DemoDataSeeder(DemoOrderRepository demoOrderRepository, DocumentRepository documentRepository,
            RagService ragService, PlatformTransactionManager transactionManager) {
        this.demoOrderRepository = demoOrderRepository;
        this.documentRepository = documentRepository;
        this.ragService = ragService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        seedOrders();
        seedKnowledgeDocuments();
    }

    void seedOrders() {
        transactionTemplate.executeWithoutResult(status -> {
            for (DemoOrderSeed seed : ORDER_SEEDS) {
                if (!demoOrderRepository.existsByOrderIdAndOwnerId(seed.orderId(),
                        SecurityIdentity.DEFAULT_OWNER_ID)) {
                    demoOrderRepository.save(seed.toEntity());
                }
            }
        });
    }

    void seedKnowledgeDocuments() {
        for (KnowledgeDocumentSeed seed : KNOWLEDGE_DOCUMENT_SEEDS) {
            seedKnowledgeDocument(seed.title(), seed.content());
        }
        for (DemoOrderSeed seed : ORDER_SEEDS) {
            seedKnowledgeDocument(seed.documentTitle(), seed.documentContent());
        }
    }

    private void seedKnowledgeDocument(String title, String content) {
        if (documentRepository.existsByOwnerIdAndTitleAndIndexStatusNot(SecurityIdentity.DEFAULT_OWNER_ID, title,
                DocumentIndexStatus.DELETED)) {
            return;
        }
        try {
            ragService.saveDocument(new DocumentRequest(title, content));
        }
        catch (RuntimeException ex) {
            log.warn("Failed to seed demo knowledge document {}", title, ex);
        }
    }

    private record KnowledgeDocumentSeed(String title, String content) {
    }

    private record DemoOrderSeed(
            String orderId,
            String customerName,
            String status,
            boolean paid,
            BigDecimal amount,
            String currency,
            String carrier,
            String trackingNumber,
            LocalDate estimatedDelivery,
            String latestEvent,
            String nextAction,
            String serviceGuidance) {

        private DemoOrderEntity toEntity() {
            return new DemoOrderEntity(orderId, customerName, status, paid, amount, currency, carrier, trackingNumber,
                    estimatedDelivery, latestEvent, nextAction);
        }

        private String documentTitle() {
            return "Demo customer order " + orderId;
        }

        private String documentContent() {
            return """
                    Demo customer service order record.

                    Order id: %s
                    Customer: %s
                    Status: %s
                    Paid: %s
                    Amount: %s %s
                    Carrier: %s
                    Tracking number: %s
                    Latest logistics event: %s
                    Estimated delivery: %s

                    Service guidance:
                    %s
                    """.formatted(
                    orderId,
                    customerName,
                    status,
                    paid,
                    amount.toPlainString(),
                    currency,
                    carrier == null ? "(none)" : carrier,
                    trackingNumber == null ? "(none)" : trackingNumber,
                    latestEvent,
                    estimatedDelivery == null ? "(none)" : estimatedDelivery,
                    serviceGuidance);
        }
    }

}
