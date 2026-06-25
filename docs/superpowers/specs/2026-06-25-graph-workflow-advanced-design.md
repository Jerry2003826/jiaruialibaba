# Graph Workflow Advanced Nodes Design

**Date:** 2026-06-25  
**Status:** Approved (user requested superpowers continuation)

## Goal

Extend workflow DSL with **subgraph**, **controlled loop**, and **dynamic tool expansion** for Graph (and Simple) runtimes, using composite opaque nodes that preserve per-node trace.

## Non-Goals

- Arbitrary cyclic graphs (only structured loop-back)
- LLM-generated dynamic graphs
- Nested parallel inside loop body
- Native Spring AI `StateGraph` subgraph embedding (use opaque composite execution first)

## Approach

### Composite opaque nodes

| Type | Behavior |
|------|----------|
| `subgraph` | Load saved definition by `definitionId` (+ optional `version`), run nested workflow on **parent runId** with namespaced node ids `{subgraphNodeId}::{nestedId}`, merge outputs |
| `loop` | While condition + `maxIterations`, execute linear body nodes inline with trace |
| `loop_back` | Compile-time marker ending loop body; edge back to `loop` (not executed standalone) |
| `dynamic` | Resolve `itemsFrom` template to list; run `tool` action per item sequentially |

Loop body / `loop_back` nodes are **composite-scoped**: excluded from top-level Graph edges; executed inside `loop` node.

### Compiler

- New edge conditions: `body`, `exit` (loop node)
- Allow single cycle: `loop_back -> loop` only
- Extract `WorkflowLoopBlock(loopNodeId, exitNodeId, bodyNodeIds, maxIterations)`
- `compositeScopedNodeIds`: body nodes + `loop_back`

### Runtime

- `WorkflowInlineExecutionService`: loop body trace, subgraph nested run (parent runId + namespaced ids), dynamic tools
- `SimpleWorkflowRuntime`: after `loop`, jump to `exit` target
- `GraphWorkflowRuntime`: register only container nodes; single exit edge from `loop`

### State

- `WorkflowExecutionState`: `loopIteration`, `lastRouteKey` (optional), dynamic outputs

## Run Graph Visualization

`GET /api/workflows/runs/{runId}/graph` overlays trace steps onto the compiled DSL:

- **Dynamic**: synthetic tool steps `{dynamicId}:dynamic:{index}:{toolName}` appear as `children` on the dynamic container.
- **Subgraph**: nested steps `{subgraphId}::{nestedNodeId}` appear as `children` on the subgraph container (same parent runId).
- **Loop**: body node steps grouped under loop container; `iterations` derived from body step count; `loop_back` status derived from loop execution.
- **Parallel**: synthetic branch ids `workflow_branch_{parallel}_{branchStart}` plus branch node steps; `parallelGroup` on branch nodes.

Mermaid output adds `subgraph` clusters for parallel blocks and loop bodies when advanced structure is present. Simple condition/linear graphs keep prior mermaid format.

## Success Criteria

- Validate/compile/run workflows with subgraph, loop, dynamic
- Graph + Simple parity tests
- README + schema registry updated
- `./mvnw test` green
