# Graph Workflow Dev/Demo Completion Design

**Date:** 2026-06-25  
**Status:** Approved (user requested plan + full implementation)

## Goal

Make Spring AI Alibaba **Graph Workflow runtime** the default dev/demo experience: visible in health/workbench, validated at startup, parity-tested with `SimpleWorkflowRuntime`, and documented.

## Non-Goals

- Subgraph mapping, cycles, dynamic node expansion
- Per-request runtime switching API
- Replacing synthetic branch tasks with native multi-step parallel edges

## Current State

`GraphWorkflowRuntime` already executes linear DAG, condition branches, and restricted parallel/join via Spring AI Alibaba `StateGraph`. Dev defaults to `simple`; health and workbench do not expose workflow runtime.

## Design

### 1. Dev default runtime = graph

- Add profile `workflow-graph` with `demo.workflow.runtime: ${DEMO_WORKFLOW_RUNTIME:graph}`
- Include in `spring.profiles.group.dev`: `dev,alibaba-strict,postgres,workflow-graph`
- Keep global `application.yml` default `simple` and `WorkflowRuntimeConfig.matchIfMissing=true` so unit tests stay on simple without extra config
- Update `.env.example` to `DEMO_WORKFLOW_RUNTIME=graph`

### 2. Startup validation

- `WorkflowRuntimeValidator` (implements `InitializingBean`): allow only `simple` or `graph`; fail fast with clear message on invalid value

### 3. Health + workbench visibility

Extend `HealthResponse`:

- `workflowRuntime` (string: `simple` | `graph`)
- `workflowRequirePublishedForRun` (boolean)

`AlibabaHealthService` reads `WorkflowRuntimeProperties`.

Frontend Runtime panel shows workflow runtime and publish guard; fix RAG hint text (PostgreSQL, not H2).

### 4. Test parity

Shared test helpers in `WorkflowRuntimeTestSupport` for flaky/slow/map-echo tool providers.

Add to `GraphWorkflowRuntimeTest`:

- condition false branch only
- `{{nodes.xxx}}` cross-node reference
- retry + attempts trace
- timeout + failed attempt trace

Add `GraphWorkflowApplicationTest`: `@SpringBootTest` with `demo.workflow.runtime=graph` asserts `GraphWorkflowRuntime` bean.

Extend `AlibabaHealthServiceTest`, `FrontendStaticAssetsTest`, add `WorkflowRuntimeValidatorTest`.

### 5. Documentation

- README Workflow Runtime: dev defaults to graph via profile group
- Fix outdated "没有前端画布" line
- Health field list includes workflow runtime

## Success Criteria

- `./mvnw test` passes
- Dev startup uses `GraphWorkflowRuntime` unless `DEMO_WORKFLOW_RUNTIME=simple`
- `GET /api/health` returns `workflowRuntime=graph`
- Workbench Runtime panel shows workflow runtime
