package com.example.agentdemo.demo;

import com.example.agentdemo.common.BusinessException;
import com.example.agentdemo.workflow.WorkflowDefinition;
import com.example.agentdemo.workflow.WorkflowDefinitionResponse;
import com.example.agentdemo.workflow.WorkflowDefinitionSaveRequest;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Predicate;

@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "demo.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoWorkflowSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoWorkflowSeeder.class);

    private final WorkflowDefinitionService workflowDefinitionService;
    private final TransactionOperations transactionOperations;

    @Autowired
    public DemoWorkflowSeeder(WorkflowDefinitionService workflowDefinitionService,
            PlatformTransactionManager transactionManager) {
        this(workflowDefinitionService, new TransactionTemplate(transactionManager));
    }

    DemoWorkflowSeeder(WorkflowDefinitionService workflowDefinitionService) {
        this(workflowDefinitionService, (TransactionOperations) null);
    }

    private DemoWorkflowSeeder(WorkflowDefinitionService workflowDefinitionService,
            TransactionOperations transactionOperations) {
        this.workflowDefinitionService = workflowDefinitionService;
        this.transactionOperations = transactionOperations;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncDemoWorkflow(DemoWorkflowTemplate.customerServiceWorkflowRequest(),
                DemoWorkflowTemplate::needsSync,
                "customer-service");
        syncDemoWorkflow(DemoWorkflowTemplate.travelExpenseConditionWorkflowRequest(),
                DemoWorkflowTemplate::travelExpenseConditionWorkflowNeedsSync,
                "travel-expense-conditions");
    }

    void syncCustomerServiceWorkflow() {
        syncDemoWorkflow(DemoWorkflowTemplate.customerServiceWorkflowRequest(),
                DemoWorkflowTemplate::needsSync,
                "customer-service");
    }

    void syncTravelExpenseConditionWorkflow() {
        syncDemoWorkflow(DemoWorkflowTemplate.travelExpenseConditionWorkflowRequest(),
                DemoWorkflowTemplate::travelExpenseConditionWorkflowNeedsSync,
                "travel-expense-conditions");
    }

    private void syncDemoWorkflow(WorkflowDefinitionSaveRequest template,
            Predicate<WorkflowDefinition> needsSync,
            String logName) {
        try {
            if (transactionOperations == null) {
                syncDemoWorkflowInTransaction(template, needsSync, logName);
            }
            else {
                transactionOperations.executeWithoutResult(status ->
                        syncDemoWorkflowInTransaction(template, needsSync, logName));
            }
        }
        catch (BusinessException exception) {
            log.warn("Skipped demo {} workflow sync because {}: {}",
                    logName, exception.getCode(), exception.getMessage());
        }
    }

    private void syncDemoWorkflowInTransaction(WorkflowDefinitionSaveRequest template,
            Predicate<WorkflowDefinition> needsSync,
            String logName) {
        workflowDefinitionService.list().stream()
                .filter(definition -> template.name().equals(definition.name()))
                .findFirst()
                .ifPresentOrElse(
                        existing -> syncExisting(existing, template, needsSync, logName),
                        () -> createAndPublish(template, logName));
    }

    private void syncExisting(WorkflowDefinitionResponse existing, WorkflowDefinitionSaveRequest template,
            Predicate<WorkflowDefinition> needsSync,
            String logName) {
        if (!needsSync.test(existing.workflowDefinition())) {
            return;
        }
        WorkflowDefinitionResponse updated = workflowDefinitionService.update(existing.definitionId(), template);
        workflowDefinitionService.publish(updated.definitionId());
        log.info("Updated demo {} workflow {}", logName, updated.definitionId());
    }

    private void createAndPublish(WorkflowDefinitionSaveRequest template, String logName) {
        WorkflowDefinitionResponse created = workflowDefinitionService.save(template);
        workflowDefinitionService.publish(created.definitionId());
        log.info("Created demo {} workflow {}", logName, created.definitionId());
    }
}
