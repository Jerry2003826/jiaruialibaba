# Graph Parallel Join Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GraphWorkflowRuntime` support the existing platform DSL `parallel -> branch nodes -> join` topology that `SimpleWorkflowRuntime` already supports.

**Architecture:** Keep `WorkflowDefinition` as the product DSL and keep `WorkflowCompiler` as the topology gate. Map `parallel` fan-out to Spring AI Alibaba Graph `StateGraph.addEdge(String, List<String>)` using the current `spring-ai-alibaba-graph-core 1.1.2.2` API. The Graph core compiler only follows the first successor of each parallel target when finding the common target, so multi-step branches are represented as synthetic branch task nodes in the Graph; each branch task executes the original DSL branch nodes in order and still writes the original node-level trace. Do not add nested parallel, loops, subgraphs, or a new DSL.

**Tech Stack:** Java 21, Spring Boot 3.5.7, Spring AI Alibaba Graph Core 1.1.2.2, JUnit 5, Mockito, Maven.

---

### Task 1: Graph Runtime Tests

**Files:**
- Modify: `src/test/java/com/example/agentdemo/workflow/GraphWorkflowRuntimeTest.java`

- [x] **Step 1: Replace the current Graph rejection test with an execution test**

Use a workflow with `start -> parallel_1 -> tool_a/tool_b -> join_1 -> end`.

Expected assertions:
- `runtime.run(...)` succeeds.
- `result.steps()` contains `start`, `parallel_1`, `tool_a`, `tool_b`, `join_1`, `end`.
- `result.output()` contains `branchOutputs.tool_a` and `branchOutputs.tool_b`.
- `traceService.completeStep("step-workflow_node_join_1", ...)` receives a map containing `branchOutputs`.

- [x] **Step 2: Run the Graph workflow tests and confirm they fail before implementation**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.GraphWorkflowRuntimeTest' test
```

Expected: failure while `GraphWorkflowRuntime` still rejects `parallel/join`.

### Task 2: Graph Runtime Fan-out/Fan-in

**Files:**
- Modify: `src/main/java/com/example/agentdemo/workflow/GraphWorkflowRuntime.java`

- [x] **Step 1: Remove the early `hasParallelJoin()` rejection**

Delete the `WORKFLOW_UNSUPPORTED` guard that rejects `parallel/join`.

- [x] **Step 2: Map DSL edges to Graph fan-out/fan-in APIs**

In `addOutgoingEdges(...)`:
- For `parallel`, call `graph.addEdge(node.id(), syntheticBranchNodeIds)`.
- For ordinary single outgoing edges, keep `graph.addEdge(node.id(), target)`.
- For `condition`, keep `addConditionalEdges`.

When adding edges, skip original branch-internal edges in the outer Graph. Add one synthetic branch node per branch start and connect each synthetic node to the common `join`. The synthetic node executes original branch nodes sequentially with branch-local `WorkflowExecutionState`, preserving original `run_step` records.

- [x] **Step 3: Keep node execution semantics unchanged**

Continue to use `executeGraphNode(...)`, `WorkflowNodeRunner`, and `TraceService` for every node. Do not introduce request-scoped mutable state in singleton fields.

- [x] **Step 4: Preserve branch-local workflow state**

Use branch-local `WorkflowExecutionState` for parallel branch execution so `lastOutput`, `nodeOutputs`, and tool calls do not race between branches before `join`.

### Task 3: Verification and Docs

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/plans/2026-06-24-graph-parallel-join.md`

- [x] **Step 1: Update README**

Change Graph runtime wording from “parallel/join unsupported” to “Graph runtime supports the same restricted `parallel/join` topology as simple runtime.”

- [x] **Step 2: Run focused and full verification**

Run:

```bash
./mvnw -Dtest='com.example.agentdemo.workflow.*Test' test
./mvnw clean package
git diff --check
```

Expected:
- Workflow tests pass.
- Full package passes.
- No whitespace errors.

- [x] **Step 3: Commit and push**

Commit message:

```bash
git commit -m "Add graph workflow parallel join support"
```

Push using proxy:

```bash
git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin main
```
