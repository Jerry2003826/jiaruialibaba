# Backend Demo Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete demo-scope backend gaps: conversation memory, RAG CRUD, LLM tool calling, workflow publish guard, trace pagination, and tests.

**Architecture:** Extend existing Spring Boot services with JPA-backed conversation memory, document management, ChatClient tool callbacks, and paginated trace queries. Keep H2 + existing trace/workflow patterns.

**Tech Stack:** Java 21, Spring Boot 3.5, Spring AI ChatClient, JPA, JUnit/MockMvc

---

## Tasks

- [x] Conversation memory entity/service + Chat/RAG/ToolChat integration
- [x] RAG document list/get/delete + VectorStoreGateway.delete
- [x] Trace pagination/filter on GET /api/runs
- [x] Workflow publish guard (demo.workflow.require-published-for-run)
- [x] LLM Tool Calling via DemoToolCallbackFactory + ToolCallingAgentService
- [x] TracingToolCallback + MCP merge in DemoToolCallbackFactory
- [x] Document delete consistency (DB first, vector cleanup after)
- [x] Memory trim on append
- [x] Web tests: RagController, RunController, ChatController
- [x] Workflow publish guard E2E + TraceService pagination tests
- [x] Frontend loadRuns pagination + `./mvnw test` (171 tests)

Spec: `docs/superpowers/specs/2026-06-25-backend-demo-completion-design.md`
