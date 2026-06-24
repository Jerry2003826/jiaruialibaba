# Workflow Run Graph Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backend-only API that exports an executed saved workflow run as graph nodes, edges, execution statuses, and Mermaid text.

**Architecture:** Reuse saved workflow run metadata from `workflow_run_records`, load the exact workflow definition revision through `WorkflowDefinitionService.resolveDefinition(definitionId, version)`, and combine it with `TraceService.getRun(...)` / `TraceService.listSteps(...)`. Keep the endpoint read-only: it must not execute workflow nodes, call model/RAG/tools, or create trace records.

**Tech Stack:** Java 21, Spring Boot MVC, Spring Data JPA repositories already in place, Bean Validation, JUnit 5, AssertJ, Mockito, MockMvc.

---

### Task 1: Run Graph DTOs And Service Method

**Files:**
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowRunGraphNodeView.java`
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowRunGraphEdgeView.java`
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowRunGraphResponse.java`
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowService.java`
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowServiceDefinitionIdTest.java`

- [x] **Step 1: Write failing service tests**

Add tests covering:
- `getRunGraph("run-1")` loads the run record, exact definition version, run response and step responses.
- Response contains ordered nodes from the definition, step status/error metadata for executed nodes, and `executed=false` for skipped nodes.
- Response marks an edge as `traversed=true` only when both its source and target nodes have trace steps.
- Missing run record returns `BusinessException` with `WORKFLOW_RUN_NOT_FOUND`.

- [x] **Step 2: Run tests to verify they fail**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowServiceDefinitionIdTest' test
```

Expected: compilation fails because the run graph DTOs and service method do not exist.

- [x] **Step 3: Implement DTOs and `WorkflowService.getRunGraph`**

Implementation rules:
- `WorkflowRunGraphResponse` fields: `runId`, `definitionId`, `definitionVersion`, `RunStatus status`, `WorkflowValidationSummary summary`, `List<WorkflowRunGraphNodeView> nodes`, `List<WorkflowRunGraphEdgeView> edges`, `String mermaid`.
- `WorkflowRunGraphNodeView` fields: `id`, `type`, `label`, `boolean executed`, `StepStatus status`, `String stepId`, `String errorMessage`.
- `WorkflowRunGraphEdgeView` fields: `from`, `to`, `condition`, `label`, `boolean traversed`.
- Mermaid must use generated aliases (`n0`, `n1`) and escaped labels.
- Node Mermaid labels include node id/type and status (`NOT_EXECUTED` when skipped).
- Do not inject runtime, RAG, model, tool gateway, or `TraceService.createRun`.

- [x] **Step 4: Run service tests to verify they pass**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowServiceDefinitionIdTest' test
```

Expected: service tests pass.

### Task 2: Controller Route And Web Binding

**Files:**
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowController.java`
- Modify: `src/test/java/com/example/agentdemo/workflow/WorkflowControllerTest.java`
- Modify: `src/test/java/com/example/agentdemo/workflow/WorkflowControllerWebTest.java`

- [x] **Step 1: Write controller tests**

Add:
- Direct controller test calling `getRunGraph("run-1")` and verifying `ApiResponse.ok`.
- MockMvc test for `GET /api/workflows/runs/run-1/graph` returning JSON graph data.

- [x] **Step 2: Run focused controller tests**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowControllerTest,com.example.agentdemo.workflow.WorkflowControllerWebTest' test
```

Expected: compilation fails until the controller route is implemented.

- [x] **Step 3: Implement route**

Add:
- `GET /api/workflows/runs/{runId}/graph`
- Return type `ApiResponse<WorkflowRunGraphResponse>`
- Controller method only delegates to `workflowService.getRunGraph(runId)`.

- [x] **Step 4: Run workflow focused tests**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowServiceDefinitionIdTest,com.example.agentdemo.workflow.WorkflowControllerTest,com.example.agentdemo.workflow.WorkflowControllerWebTest' test
```

Expected: all focused tests pass.

### Task 3: README, Review, And Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-06-24-workflow-run-graph-export.md`

- [x] **Step 1: Update README**

Document:
- `GET /api/workflows/runs/{runId}/graph` in the API list.
- A curl example.
- That the endpoint works for runs created from saved workflow definitions.
- That it combines definition revision metadata with trace step statuses and returns Mermaid text for visual canvas/run inspection.
- That inline workflow runs are still inspectable through `/api/runs/{runId}/steps`, but this graph export currently requires `workflow_run_records`.

- [x] **Step 2: Dispatch independent code review**

Ask a read-only reviewer to inspect:
- Scope: no execution side effects, no TraceService create/write calls.
- DTO clarity and no new `Map<String,Object>` response abuse.
- Controller remains thin.
- README matches endpoint behavior.

- [x] **Step 3: Run final verification**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowServiceDefinitionIdTest,com.example.agentdemo.workflow.WorkflowControllerTest,com.example.agentdemo.workflow.WorkflowControllerWebTest' test
./mvnw clean package
git diff --check
rg -n 'sk-[A-Za-z0-9]|AI_DASHSCOPE_API_KEY=.*[A-Za-z0-9]{8}|DASHVECTOR_API_KEY=.*[A-Za-z0-9]{8}|GITHUB_TOKEN=.*[A-Za-z0-9]{8}' . --glob '!target/**' --glob '!.git/**' --glob '!.env'
```

Expected:
- Focused tests pass.
- Full package passes.
- `git diff --check` has no output.
- Secret scan only reports placeholders or no output.

- [ ] **Step 4: Mark plan complete, commit and push**

Run:

```bash
git add src/main/java/com/example/agentdemo/workflow src/test/java/com/example/agentdemo/workflow README.md docs/superpowers/plans/2026-06-24-workflow-run-graph-export.md
git commit -m "Add workflow run graph export"
git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin main
```

Expected: commit succeeds and `main` pushes to GitHub.
