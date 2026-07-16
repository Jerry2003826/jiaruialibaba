# Workflow Spec Gate Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a two-stage natural-language workflow generation gate that asks clarifying questions or locks a business specification before generating a workflow.

**Architecture:** Add a small backend `WorkflowSpecDraftService` that returns a typed spec draft response. Add a controller route and connect the existing frontend generator button to call the spec gate first, then call the current streaming generator with the locked `generationPrompt`.

**Tech Stack:** Java 17, Spring MVC, Jackson, JUnit/MockMvc, static JavaScript workbench.

---

### Task 1: Backend Spec Draft Service

**Files:**
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowSpecDraftRequest.java`
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowSpecDraftResponse.java`
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowSpecDraftService.java`
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowSpecDraftServiceTest.java`

- [ ] Write failing service tests for `NEEDS_CLARIFICATION` and `READY`.
- [ ] Implement request/response records and service parsing.
- [ ] Verify service tests pass.

### Task 2: Controller Route

**Files:**
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowController.java`
- Modify: `src/test/java/com/example/agentdemo/workflow/WorkflowControllerWebTest.java`

- [ ] Write failing MockMvc test for `POST /api/workflows/spec-drafts`.
- [ ] Inject the spec service and expose the route.
- [ ] Verify controller tests pass.

### Task 3: Frontend Two-Stage Generation

**Files:**
- Modify: `src/main/resources/static/js/api.js`
- Modify: `src/main/resources/static/js/workflow.js`
- Modify: `src/main/resources/static/styles.css`
- Modify: `src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java`

- [ ] Write failing static asset assertions for the new API and UI functions.
- [ ] Add `specDraft` endpoint constant.
- [ ] Add first-click spec draft behavior and second-click generation behavior.
- [ ] Render clarification questions and locked spec cards in the existing preview panel.
- [ ] Verify static frontend tests pass.

### Task 4: Verification

**Files:**
- All touched files.

- [ ] Run targeted backend tests.
- [ ] Run frontend static asset tests.
- [ ] Run `node --check` on changed JavaScript.
- [ ] Run `git diff --check`.
- [ ] Restart local service and verify `/healthz`.
