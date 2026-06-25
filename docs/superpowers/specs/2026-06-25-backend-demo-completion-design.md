# Backend Demo Completion Design

**Date:** 2026-06-25  
**Scope:** Demo 承诺范围内后端补全（用户已确认）

## Goal

补齐 `agent-backend-demo` 后端在 Demo 范围内的简化实现缺口，使 README 描述的能力在行为上名副其实，并补全关键测试。

## In Scope

1. **Conversation Memory** — `conversationId` 驱动多轮上下文，H2 持久化
2. **RAG Document CRUD** — 列表、详情、删除（含 chunk + 向量清理）
3. **LLM Tool Calling** — `ToolCallingAgentService` 改用 ChatClient + ToolCallback，规则匹配仅作无 API Key fallback
4. **Workflow Publish Guard** — 按已保存定义运行时校验 `PUBLISHED` 状态
5. **Trace Pagination** — `GET /api/runs` 支持分页与 type/status 过滤
6. **Backend Tests** — Chat、SSE、ToolCallingAgent、RAG CRUD、Trace 分页、Publish guard

## Out of Scope

- Workflow 循环/子图/Graph 原生 parallel 增强
- 租户、权限、节点市场、Cairn Memory、MCP 熔断审计
- 前端改动
- Spring AI Agent Framework / ReactAgent 引入

## Architecture

### Conversation Memory

- 新表 `conversation_messages`：`id`, `conversation_id`, `role`, `content`, `created_at`
- `ConversationMemoryService`：resolveId、loadRecent、appendUser、appendAssistant
- 配置 `demo.chat.memory.max-messages`（默认 20）
- `ChatService`、`RagService`、`ToolCallingAgentService` 在模型调用前后读写 memory
- `AiModelService` 新增 `generate(system, history, userMessage)` 与 `stream(...)` 变体

### RAG Document CRUD

- `GET /api/rag/documents?page&size`
- `GET /api/rag/documents/{id}`
- `DELETE /api/rag/documents/{id}`
- `DocumentManagementService` 协调 H2 document/chunk 与 `VectorStoreGateway.delete(ids)`
- `VectorStoreGateway` 新增 `delete(Collection<String> vectorIds)`；DashVector 用 DeleteDocRequest

### LLM Tool Calling

- `DemoToolCallbackFactory` 为本地工具构建 `FunctionToolCallback`，委托 `ToolGatewayService`
- MCP 启用时复用 `McpToolProvider` 暴露的 callbacks（新增 accessor）
- `TracingToolCallback` 包装器写入 run_step
- `ToolCallingAgentService`：有 ChatClient 时走 LLM tool loop；否则保留现有规则 fallback

### Workflow Publish Guard

- 配置 `demo.workflow.require-published-for-run`（默认 `true`）
- `definitionId` + 无 version：当前 definition 必须 `PUBLISHED`
- `definitionId` + version：对应 revision 必须 `PUBLISHED`
- inline `workflowDefinition` 不受约束

### Trace Pagination

- `GET /api/runs?type&status&page&size` 返回 `RunPageResponse`
- `RunRepository` 增加 `JpaSpecificationExecutor` 或 query method

## Error Handling

| Code | When |
|------|------|
| `CONVERSATION_NOT_FOUND` | N/A — 空 conversation 自动创建 |
| `DOCUMENT_NOT_FOUND` | GET/DELETE 不存在的文档 |
| `WORKFLOW_DEFINITION_NOT_PUBLISHED` | 未发布定义被运行 |
| `RUN_QUERY_INVALID` | 分页参数非法 |

## Testing Strategy

- 单元测试：ConversationMemoryService、DocumentManagementService、DemoToolCallbackFactory、Workflow publish guard
- Web 测试：RagController CRUD、RunController 分页、ChatController
- 集成测试：ToolCallingAgentService（mock ChatClient / fallback 路径）
