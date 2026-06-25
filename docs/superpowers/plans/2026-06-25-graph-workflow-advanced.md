# Graph Workflow Advanced Nodes Implementation Plan

> **For agentic workers:** Use superpowers:subagent-driven-development.

**Goal:** Subgraph, loop, dynamic node types with compiler validation and runtime execution.

Spec: `docs/superpowers/specs/2026-06-25-graph-workflow-advanced-design.md`

---

### Task 1: State + loop block model
- [x] `WorkflowLoopBlock`, extend `WorkflowExecutionPlan`
- [x] `WorkflowExecutionState` loop iteration helpers

### Task 2: Compiler
- [x] Support types + edge conditions `body`/`exit`
- [x] Loop validation, composite-scoped nodes, allow loop_back cycle

### Task 3: Inline execution service
- [x] `WorkflowInlineExecutionService` loop/subgraph/dynamic

### Task 4: Node executor + schemas
- [x] Wire new node types, add operators `greaterthan`/`lessthan`

### Task 5: Runtimes
- [x] `SimpleWorkflowRuntime` + `GraphWorkflowRuntime` composite handling

### Task 6: Tests + README
- [x] Compiler, runtime, integration tests; docs

### Task 7: Run graph visualization + subgraph trace fix
- [x] Subgraph runs on parent runId with `{subgraphNodeId}::{nestedId}` namespaced trace steps
- [x] `WorkflowRunGraphNodeView` composite fields + `WorkflowRunGraphStepView`
- [x] Run graph service/renderer: dynamic/loop/subgraph/parallel children + mermaid clusters
- [x] Frontend trace step grouping; `WorkflowSubgraphRunGraphE2ETest` with real H2 trace
