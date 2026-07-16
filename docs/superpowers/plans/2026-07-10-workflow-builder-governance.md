# Workflow Builder Governance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make natural-language workflow generation retrieve Builder-specific design knowledge, enforce deterministic governance rules, run a complete applicable regression suite, and only replace or publish a workflow after it passes.

**Architecture:** A versioned rule-pack catalog is the single source for hard rules, RAG guidance, repair hints, and mandatory evaluation cases. A hidden owner-scoped knowledge base indexes the guidance entries, while deterministic validators and runtime evaluations remain authoritative. Generation, edit, repair, validation, and publish share one governance orchestrator; the frontend applies only `READY` outcomes.

**Tech Stack:** Java 25, Spring Boot 3.5, Jackson, Spring Data JPA, Flyway, existing RAG/knowledge services, vanilla JavaScript/CSS, JUnit 5, AssertJ, Mockito, MockMvc.

## Global Constraints

- Use only node types returned by `WorkflowNodeSchemaRegistry`; never add a new workflow node type.
- Use only executable tools returned by `ToolGatewayService`; never substitute `getCurrentTime` or `calculate` for a business query or action.
- Built-in governance rules are read-only in v1; ordinary knowledge-base APIs must not expose or mutate the managed Builder knowledge base.
- RAG evidence is untrusted guidance and cannot override hard rules, tool policy, permissions, or node schemas.
- Save remains permissive; generated/edited/repaired candidates may replace the canvas only when governance status is `READY`; publish must rerun governance and block failures.
- Activate a generic `core` rule pack for every workflow and `customer-service-ecommerce` when the locked specification domain is customer service/e-commerce.
- Run every applicable mandatory case before returning `READY`, with at most 8 cases, concurrency 2, 90 seconds per case, and the existing 10-minute generation stream budget.
- Keep the existing three-attempt Diagnose -> Plan -> Implement -> Verify repair loop; retry an infrastructure-failed case once before treating the candidate as blocked.
- Preserve backward compatibility for existing saved definitions and the legacy singular `testResult` response field.
- Work with the current dirty worktree; do not revert or overwrite unrelated existing changes, and do not stage or commit user-owned changes.

---

### Task 1: Versioned Governance Rule Packs

**Files:**
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowRulePack.java`
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowGovernanceRule.java`
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowEvaluationCase.java`
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowRuleCatalog.java`
- Create: `src/main/resources/workflow-builder/rules/core.json`
- Create: `src/main/resources/workflow-builder/rules/customer-service-ecommerce.json`
- Test: `src/test/java/com/example/agentdemo/workflow/governance/WorkflowRuleCatalogTest.java`

**Interfaces:**
- Produces: `List<WorkflowRulePack> WorkflowRuleCatalog.activePacks(String domain)`, `String detectDomain(WorkflowDefinition definition)`, and `List<WorkflowRulePack> allPacks()`.
- Rule packs expose `id`, `version`, `domains`, `rules`, `knowledgeEntries`, and `testCases` as immutable values.

- [ ] **Step 1: Write failing catalog tests**

  Test that `activePacks("customer-service-ecommerce")` returns `core` followed by `customer-service-ecommerce`, an unrelated domain returns only `core`, a saved graph containing客服/订单/物流 semantics is redetected as customer service, rule IDs are unique, versions are nonblank, and customer-service cases include tracking, missing order ID, urge shipping, return/exchange, compound damage, vague complaint, greeting, and missing-order/tool-failure behavior.

- [ ] **Step 2: Verify RED**

  Run `./mvnw -Dtest=WorkflowRuleCatalogTest test` and confirm compilation fails because the catalog and records do not exist.

- [ ] **Step 3: Implement immutable records and JSON loader**

  Load both classpath JSON resources at startup, reject duplicate pack/rule/case IDs, blank versions, unknown severities, and packs without `core`. Keep detector names data-only; executable detector implementations arrive in Task 3.

- [ ] **Step 4: Add the initial rule content**

  Core rules cover registered nodes/tools, template/output-contract consistency, customer-readable final output, and unsupported business claims. The customer-service pack covers authoritative CRM/VIP data, real logistics/order lookup, missing-order clarification, multi-issue preservation, low-confidence clarification, and tool failure/no-result fallback.

- [ ] **Step 5: Verify GREEN**

  Run `./mvnw -Dtest=WorkflowRuleCatalogTest test` and confirm all catalog tests pass.

### Task 2: Hidden Builder Knowledge Base and Retrieval

**Files:**
- Create: `src/main/resources/db/migration/V15__builder_governance_knowledge.sql`
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowBuilderKnowledgeService.java`
- Modify: `src/main/java/com/example/agentdemo/knowledge/KnowledgeBaseEntity.java`
- Modify: `src/main/java/com/example/agentdemo/knowledge/KnowledgeBaseRepository.java`
- Modify: `src/main/java/com/example/agentdemo/knowledge/KnowledgeBaseAccessService.java`
- Modify: `src/main/java/com/example/agentdemo/knowledge/KnowledgeBaseService.java`
- Modify: `src/main/java/com/example/agentdemo/knowledge/KnowledgeIngestionService.java`
- Modify: `src/main/java/com/example/agentdemo/knowledge/KnowledgeDocumentService.java`
- Modify: `src/main/java/com/example/agentdemo/knowledge/KnowledgeSearchService.java`
- Test: `src/test/java/com/example/agentdemo/workflow/governance/WorkflowBuilderKnowledgeServiceTest.java`
- Test: `src/test/java/com/example/agentdemo/knowledge/KnowledgeBaseServiceTest.java`
- Test: `src/test/java/com/example/agentdemo/knowledge/KnowledgeSearchServiceTest.java`
- Test: `src/test/java/com/example/agentdemo/migration/PostgresFlywayMigrationIntegrationTest.java`

**Interfaces:**
- Produces: `List<Citation> WorkflowBuilderKnowledgeService.retrieve(String domain, String query, int topK)`.
- Managed KB metadata: `purpose = WORKFLOW_BUILDER`, `system_managed = true`; existing rows migrate to `BUSINESS`, `false`.

- [ ] **Step 1: Write failing persistence/isolation tests**

  Test lazy creation of one managed KB per owner, idempotent rule-pack document synchronization by content hash, retrieval scoped to the current owner, exclusion of managed KBs from ordinary `listKnowledgeBases()`, and rejection of direct public get/search/document/reindex/delete access to a managed KB.

- [ ] **Step 2: Verify RED**

  Run the two focused service tests and confirm failures are caused by missing managed-KB metadata/service behavior.

- [ ] **Step 3: Add the Flyway migration and entity/repository support**

  Add non-null purpose/system-managed columns with backward-compatible defaults and repository methods that are only used internally for the current owner. Split access into a public lookup that rejects managed KBs and an internal Builder lookup; do not add public CRUD parameters for these fields.

- [ ] **Step 4: Implement managed corpus synchronization and retrieval**

  Convert each active rule's rationale, anti-patterns, examples, and repair hint into a text document. Synchronize on first Builder request, use existing indexing/keyword fallback, retrieve at most 6 citations, and return an empty list plus a warning signal when retrieval fails.

- [ ] **Step 5: Verify GREEN and migration compatibility**

  Run the focused knowledge tests and `PostgresFlywayMigrationIntegrationTest`; confirm existing business KB behavior is unchanged and migration v15 succeeds on PostgreSQL.

### Task 3: Builder Context and Deterministic Static Governance

**Files:**
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowBuilderContextService.java`
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowGovernanceFinding.java`
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowGovernanceReport.java`
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowGovernanceService.java`
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowGenerationService.java`
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowStructuredOutputAutoconfigurer.java`
- Test: `src/test/java/com/example/agentdemo/workflow/governance/WorkflowGovernanceServiceTest.java`
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowGenerationServiceTest.java`
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowStructuredOutputAutoconfigurerTest.java`

**Interfaces:**
- Produces: `WorkflowBuilderContext build(String domain, String lockedSpec, String previousFailure)` containing active packs, exact node schemas, executable tools, and retrieved citations.
- Produces: `WorkflowGovernanceReport evaluateStatic(WorkflowDefinition definition, WorkflowBuilderContext context)`.
- Finding fields: `ruleId`, `severity` (`BLOCK` or `WARN`), `phase`, `message`, `nodeIds`, `repairHint`, and `evidence`.

- [ ] **Step 1: Write failing context/governance tests**

  Cover unregistered node/tool, `getCurrentTime` presented as CRM/logistics, missing structured fields referenced by conditions, conflicting prompt/schema fields, customer-facing JSON, unsupported claims of successful queries/actions, single-label output when the locked spec requires compound issues, and missing fallback for required data.

- [ ] **Step 2: Write the autoconfigurer regression test**

  Reproduce the current transfer-node bug: a final/transfer LLM mentioning customer service must retain its manual schema or text mode and must not receive `customer_service_intent`; only a classifier whose parsed field is consumed downstream may be auto-configured.

- [ ] **Step 3: Verify RED**

  Run the three focused test classes and confirm the new rules fail while existing structural behavior remains green.

- [ ] **Step 4: Implement dynamic generation context**

  Remove the `getCurrentTime` fallback instruction. Append a bounded JSON catalog of real node schemas and executable tools plus an `UNTRUSTED_BUILDER_KNOWLEDGE` section to generation/edit/repair prompts. State explicitly that missing capabilities require clarification or honest handoff rather than simulated execution.

- [ ] **Step 5: Implement static detectors and narrow autoconfiguration**

  Keep topology compilation authoritative, run rule detectors afterward, and return every finding in deterministic rule-ID order. Use the explicit locked-spec domain during generation and union it with graph-based domain detection; publish uses graph-based detection so a client cannot bypass a rule pack by omitting metadata. Block only `BLOCK` findings; retain `WARN` findings in the report.

- [ ] **Step 6: Verify GREEN**

  Run the focused tests and confirm every reproduced defect is detected without changing valid customer-service classifiers.

### Task 4: Full Runtime Evaluation and Automatic Repair

**Files:**
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowEvaluationCaseResult.java`
- Create: `src/main/java/com/example/agentdemo/workflow/governance/WorkflowEvaluationService.java`
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowGenerationResponse.java`
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowGenerationService.java`
- Modify: `src/main/java/com/example/agentdemo/config/WorkflowRuntimeProperties.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/example/agentdemo/workflow/governance/WorkflowEvaluationServiceTest.java`
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowGenerationServiceTest.java`

**Interfaces:**
- Generation status: `READY`, `BLOCKED`, or `INFRA_ERROR`.
- `WorkflowGenerationResponse` adds `status`, `governanceReport`, and `testResults`; legacy `testResult` maps to the primary successful case.
- Evaluation results expose case ID, input, status, run ID, executed path, assertions, output summary, and error category.

- [ ] **Step 1: Write failing evaluation tests**

  Test all applicable cases are selected (maximum 8), concurrency never exceeds 2, each run has a 90-second budget, parsed fields/tool calls/final answer are asserted, and internal JSON or fabricated success is rejected.

- [ ] **Step 2: Write failing repair/outcome tests**

  Test static failures skip runtime, design failures feed the complete report into the Superpowers repair prompt, an infrastructure failure reruns the same candidate once, three failed candidate attempts return structured `BLOCKED`/`INFRA_ERROR`, and only a fully passing candidate returns `READY`.

- [ ] **Step 3: Verify RED**

  Run the evaluation and generation tests; confirm failures are due to missing multi-case/report behavior.

- [ ] **Step 4: Implement bounded evaluation**

  Use a two-worker executor, open/close `WorkflowRunBudgetRegistry` per case, trace every case, cancel unfinished work at the overall deadline, and preserve distinction between design failures and provider/network/time-budget failures.

- [ ] **Step 5: Integrate the generation loop**

  Parse model-provided supplemental cases but never allow them to replace mandatory pack cases. For nonfinal attempts, fail fast on the first blocker; the candidate returned as `READY` must complete the entire applicable suite.

- [ ] **Step 6: Verify GREEN**

  Run the focused tests and confirm deterministic reports, bounded execution, retry policy, and backward-compatible `testResult` serialization.

### Task 5: Shared APIs, Publish Gate, and Frontend Evidence

**Files:**
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowController.java`
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowService.java`
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowDefinitionService.java`
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowSpecDraftService.java`
- Modify: `src/main/resources/static/js/api.js`
- Modify: `src/main/resources/static/js/workflow.js`
- Modify: `src/main/resources/static/styles.css`
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowControllerWebTest.java`
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowDefinitionServiceTest.java`
- Test: `src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java`

**Interfaces:**
- Locked spec adds `domain`, `requiredCapabilities`, `outputAudience`, and `testCases` while preserving the existing map response.
- Add `POST /api/workflows/governance/evaluate` for a draft definition plus locked spec.
- Publish keeps its existing success response but returns `WORKFLOW_GOVERNANCE_BLOCKED` when the current definition does not pass a fresh full evaluation.
- Streaming generation emits phase/status updates and a structured final outcome; governance rejection is not represented as a network error.

- [ ] **Step 1: Write failing controller/publish/frontend tests**

  Test the evaluate endpoint, fresh publish gate, permissive save, locked-spec fields, stream status phases, `READY`-only canvas application, and rendering of summary plus expandable findings/case evidence.

- [ ] **Step 2: Verify RED**

  Run the three focused test classes and confirm expected missing API/UI behavior.

- [ ] **Step 3: Implement shared controller/service wiring**

  Make generate/edit/repair/evaluate/publish call the same governance orchestrator. Preserve the current canvas and prompt for `BLOCKED` or `INFRA_ERROR`; save remains unchanged.

- [ ] **Step 4: Implement the compact report UI**

  Default view shows status, passed/total cases, blocking count, warning count, active pack versions, and repair attempts. A native disclosure control reveals rule evidence, inputs, actual paths, outputs, and run IDs. Do not show raw model JSON as the primary status surface.

- [ ] **Step 5: Verify GREEN**

  Run controller, definition-service, static-asset, and `node --check src/main/resources/static/js/workflow.js` checks.

### Task 6: End-to-End Regression and Live Verification

**Files:**
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowBuilderGovernanceIntegrationTest.java`
- Modify only if a discovered defect requires it: implementation files from Tasks 1-5.

- [ ] **Step 1: Add the end-to-end regression test**

  Cover a generated customer-service candidate containing the historical fake CRM/logistics pattern and confirm it is repaired or blocked; cover a valid existing-node/tool workflow and confirm all applicable cases pass and the result is `READY`.

- [ ] **Step 2: Verify the focused integration test**

  Run `./mvnw -Dtest=WorkflowBuilderGovernanceIntegrationTest test`.

- [ ] **Step 3: Run full automated verification**

  Run `./mvnw test`, `node --check src/main/resources/static/js/workflow.js`, and `git diff --check`; require zero failures/errors.

- [ ] **Step 4: Restart and verify the live service**

  Restart with the repository-owned Alibaba startup script, verify `/healthz`, load `http://localhost:8080/`, generate the售后物流 workflow, inspect the governance report, and run the eight customer-service cases through the real UI/API.

- [ ] **Step 5: Request final code review**

  Review the entire implementation against the Global Constraints, fix all Critical/Important findings, rerun affected tests, and then rerun the full verification commands before reporting completion.
