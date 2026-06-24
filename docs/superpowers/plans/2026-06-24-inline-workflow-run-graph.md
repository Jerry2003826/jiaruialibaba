# Inline Workflow Run Graph

## Goal

Make `/api/workflows/runs/{runId}/graph` work for both saved-definition workflow runs and inline-definition workflow runs, while moving graph rendering out of `WorkflowService`.

## Scope

- Add a dedicated `WorkflowRunGraphService`.
- Preserve the existing saved-definition run graph behavior.
- Add fallback reconstruction from `RunResponse.input` when no `WorkflowRunRecordEntity` exists.
- Return `WORKFLOW_GRAPH_UNAVAILABLE` for non-workflow runs or workflow runs that do not contain enough graph metadata.
- Keep the endpoint read-only: no run or step trace writes.
- Update README to describe saved and inline run behavior truthfully.

## Plan

- [x] Add focused tests for saved-definition graph export, inline graph export, unavailable graph metadata, and controller delegation.
- [x] Move graph export logic from `WorkflowService` to `WorkflowRunGraphService`.
- [x] Wire `WorkflowController` to the new service and update existing tests.
- [x] Update README limitations and examples.
- [x] Run focused tests and `./mvnw clean package`.
- [ ] Commit and push through the configured proxy.
