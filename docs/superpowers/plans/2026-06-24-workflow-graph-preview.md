# Workflow Graph Preview Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a backend-only workflow graph preview API that validates a Workflow DSL and returns node/edge data plus Mermaid text for future visual canvas integration.

**Architecture:** Reuse `WorkflowCompiler` as the single source of topology and node-config validation. Add focused DTO records and a `WorkflowGraphPreviewService` so `WorkflowController` remains thin and no run trace is created during preview.

**Tech Stack:** Java 21, Spring Boot MVC, Bean Validation, JUnit 5, AssertJ, Mockito.

---

### Task 1: Graph Preview DTOs And Service

**Files:**
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowGraphPreviewRequest.java`
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowGraphNodeView.java`
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowGraphEdgeView.java`
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowGraphPreviewResponse.java`
- Create: `src/main/java/com/example/agentdemo/workflow/WorkflowGraphPreviewService.java`
- Test: `src/test/java/com/example/agentdemo/workflow/WorkflowGraphPreviewServiceTest.java`

- [x] **Step 1: Write service tests**

Add tests covering:
- Valid linear workflow returns `valid=true`, validation summary, ordered nodes, ordered edges, and Mermaid `flowchart TD`.
- Conditional workflow includes edge labels like `true` / `false`.
- Invalid workflow returns `valid=false`, empty graph payload, and the compiler error without creating run trace.

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowGraphPreviewServiceTest' test
```

Expected: compilation fails because preview DTOs/service do not exist yet.

- [x] **Step 3: Implement DTOs and service**

Implementation rules:
- Request record must use `@NotNull @Valid WorkflowDefinition workflowDefinition`.
- Response record must use typed fields, not a generic `Map<String,Object>`.
- Mermaid node ids must be generated aliases such as `n0`, `n1` so arbitrary workflow node ids cannot break Mermaid syntax.
- Mermaid labels should include workflow node id and type, with quotes/newlines escaped.
- Invalid preview should return `valid=false`, `nodes=[]`, `edges=[]`, `mermaid=""`, `summary=null`, and one `WorkflowValidationError`.

- [x] **Step 4: Run test to verify it passes**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowGraphPreviewServiceTest' test
```

Expected: all preview service tests pass.

### Task 2: Workflow Controller Route

**Files:**
- Modify: `src/main/java/com/example/agentdemo/workflow/WorkflowController.java`
- Modify: `src/test/java/com/example/agentdemo/workflow/WorkflowControllerTest.java`

- [x] **Step 1: Write controller test**

Add a test that constructs `WorkflowController` with a mocked `WorkflowGraphPreviewService`, calls `previewGraph(request)`, and verifies the response is wrapped in `ApiResponse.ok`.

- [x] **Step 2: Run focused controller test**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowControllerTest' test
```

Expected: compilation fails until the constructor and route are implemented.

- [x] **Step 3: Implement route**

Add:
- Constructor injection for `WorkflowGraphPreviewService`.
- `POST /api/workflows/preview-graph`.
- Return type `ApiResponse<WorkflowGraphPreviewResponse>`.
- No compiler or graph-building logic inside the controller.

- [x] **Step 4: Run workflow tests**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.*Test' test
```

Expected: all workflow tests pass.

### Task 3: README And Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-06-24-workflow-graph-preview.md`

- [x] **Step 1: Update README**

Document:
- `POST /api/workflows/preview-graph` in the API list.
- A curl example with a small workflow.
- That preview validates DSL and returns graph data/Mermaid but does not execute nodes or create run trace.
- That this is the backend contract for a future visual workflow canvas.

- [x] **Step 2: Mark this plan's completed checkboxes**

Update each completed checkbox from `[ ]` to `[x]` only after the implementation and verification commands have run.

- [x] **Step 3: Run final verification**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.WorkflowGraphPreviewServiceTest,com.example.agentdemo.workflow.WorkflowControllerTest,com.example.agentdemo.workflow.WorkflowControllerWebTest' test
./mvnw clean package
git diff --check
rg -n 'sk-[A-Za-z0-9]|AI_DASHSCOPE_API_KEY=.*[A-Za-z0-9]{8}|DASHVECTOR_API_KEY=.*[A-Za-z0-9]{8}|GITHUB_TOKEN=.*[A-Za-z0-9]{8}' . --glob '!target/**' --glob '!.git/**' --glob '!.env'
```

Expected:
- Focused tests pass.
- Full package passes.
- `git diff --check` has no output.
- Secret scan only reports placeholders or no output.

- [ ] **Step 4: Commit and push**

Run:

```bash
git add src/main/java/com/example/agentdemo/workflow src/test/java/com/example/agentdemo/workflow README.md docs/superpowers/plans/2026-06-24-workflow-graph-preview.md
git commit -m "Add workflow graph preview endpoint"
git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin main
```

Expected: commit succeeds and `main` pushes to GitHub.
