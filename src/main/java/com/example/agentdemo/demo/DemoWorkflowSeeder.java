package com.example.agentdemo.demo;

import com.example.agentdemo.workflow.WorkflowDefinitionResponse;
import com.example.agentdemo.workflow.WorkflowDefinitionSaveRequest;
import com.example.agentdemo.workflow.WorkflowDefinitionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("dev")
@ConditionalOnProperty(prefix = "demo.seed", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DemoWorkflowSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoWorkflowSeeder.class);

    private final WorkflowDefinitionService workflowDefinitionService;

    public DemoWorkflowSeeder(WorkflowDefinitionService workflowDefinitionService) {
        this.workflowDefinitionService = workflowDefinitionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        syncCustomerServiceWorkflow();
    }

    void syncCustomerServiceWorkflow() {
        WorkflowDefinitionSaveRequest template = DemoWorkflowTemplate.customerServiceWorkflowRequest();
        workflowDefinitionService.list().stream()
                .filter(definition -> DemoWorkflowTemplate.CUSTOMER_SERVICE_WORKFLOW_NAME.equals(definition.name()))
                .findFirst()
                .ifPresentOrElse(
                        existing -> syncExisting(existing, template),
                        () -> createAndPublish(template));
    }

    private void syncExisting(WorkflowDefinitionResponse existing, WorkflowDefinitionSaveRequest template) {
        if (!DemoWorkflowTemplate.needsSync(existing.workflowDefinition())) {
            return;
        }
        WorkflowDefinitionResponse updated = workflowDefinitionService.update(existing.definitionId(), template);
        workflowDefinitionService.publish(updated.definitionId());
        log.info("Updated demo customer-service workflow {} to structured intent template",
                updated.definitionId());
    }

    private void createAndPublish(WorkflowDefinitionSaveRequest template) {
        WorkflowDefinitionResponse created = workflowDefinitionService.save(template);
        workflowDefinitionService.publish(created.definitionId());
        log.info("Created demo customer-service workflow {}", created.definitionId());
    }
}
