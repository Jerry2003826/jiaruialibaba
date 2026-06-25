# Graph Workflow Dev/Demo Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Default dev to Graph Workflow runtime with health/workbench visibility, startup validation, test parity, and README sync.

**Architecture:** Profile `workflow-graph` in dev group; extend health DTO/service; shared workflow test providers; no changes to GraphWorkflowRuntime execution logic unless tests reveal bugs.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI Alibaba Graph, static workbench

Spec: `docs/superpowers/specs/2026-06-25-graph-workflow-design.md`

---

### Task 1: Dev profile defaults to graph runtime

**Files:**
- Create: `src/main/resources/application-workflow-graph.yml`
- Modify: `src/main/resources/application.yml` (dev profile group)
- Modify: `.env.example`

- [x] Add `application-workflow-graph.yml` with `demo.workflow.runtime: ${DEMO_WORKFLOW_RUNTIME:graph}`
- [x] Append `workflow-graph` to `spring.profiles.group.dev`
- [x] Set `.env.example` `DEMO_WORKFLOW_RUNTIME=graph`

### Task 2: Workflow runtime startup validator

**Files:**
- Create: `src/main/java/com/example/agentdemo/config/WorkflowRuntimeValidator.java`
- Create: `src/test/java/com/example/agentdemo/config/WorkflowRuntimeValidatorTest.java`

- [x] Validator throws on invalid runtime values
- [x] Unit tests for simple/graph valid, `banana` invalid

### Task 3: Health API exposes workflow runtime

**Files:**
- Modify: `HealthResponse.java`, `AlibabaHealthService.java`
- Modify: `AlibabaHealthServiceTest.java`

- [x] Add `workflowRuntime`, `workflowRequirePublishedForRun` fields
- [x] Wire `WorkflowRuntimeProperties` in health service
- [x] Update health unit test

### Task 4: Workbench runtime panel

**Files:**
- Modify: `src/main/resources/static/app.js`
- Modify: `src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java`

- [x] Display `workflowRuntime` and publish guard in `loadHealth()`
- [x] Fix RAG hint PostgreSQL wording
- [x] Assert `workflowRuntime` referenced in static assets test

### Task 5: Graph runtime test parity + integration

**Files:**
- Create: `src/test/java/com/example/agentdemo/support/WorkflowRuntimeTestSupport.java`
- Modify: `GraphWorkflowRuntimeTest.java`
- Create: `src/test/java/com/example/agentdemo/workflow/GraphWorkflowApplicationTest.java`

- [x] Extract shared tool providers + trace helpers
- [x] Add false-branch, node-ref, retry, timeout tests for graph
- [x] SpringBootTest confirms graph bean loads

### Task 6: README sync

**Files:**
- Modify: `README.md`

- [ ] Dev defaults graph via profile; health fields; remove outdated canvas line

### Task 7: Verify and commit

- [ ] `./mvnw test` all green
- [ ] Commit with descriptive message
