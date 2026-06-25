# Spring AI Alibaba Agent Backend Demo

## 项目目标

这是一个最小可运行、可扩展的 Spring AI Alibaba Agent 平台 demo。它不是完整 Dify，而是提供一个后端骨架和最小 Dify-like 工作台：Chat、SSE streaming、tool calling、RAG、workflow canvas、run trace。

## 技术栈

- Java 21
- Maven
- Spring Boot 3.5.7
- Spring AI 1.1.2
- Spring AI Alibaba 1.1.2.2
- Spring AI Alibaba DashScope starter
- Spring AI Alibaba Graph Core
- Spring AI MCP Client starter
- Spring MVC + `SseEmitter`
- Spring Data JPA
- PostgreSQL（dev 持久化；单测仍用 H2 in-memory）

## 环境变量

可以从示例文件开始：

```bash
cp .env.example .env
```

如果使用 `.env`，请只在本地加载它，不要提交真实密钥。本项目的 `.gitignore` 已忽略 `.env` 和 `.env.*`，但保留 `.env.example`。

```bash
export AI_DASHSCOPE_API_KEY=your-dashscope-api-key
export AI_DASHSCOPE_CHAT_MODEL=qwen-plus
```

`AI_DASHSCOPE_API_KEY` 不配置时，若未开启阿里严格模式，服务仍可启动，并会对 Chat/SSE/RAG 使用 fallback 响应，方便本地验证接口和 trace。开启严格模式后，必须配置完整阿里栈（DashScope Chat + Embedding + DashVector），否则启动失败。
为了兼容 DashScope Python SDK 的常见命名，项目也支持 `DASHSCOPE_API_KEY` 作为备用读取来源；推荐在本项目中继续使用 `AI_DASHSCOPE_API_KEY`。

## 阿里严格模式（Alibaba Strict Mode）

dev profile 默认组合 `alibaba-strict`，配合 `.env` 使用 DashScope + DashVector 全栈，禁用本地环境 fallback：

| 环境 fallback | 严格模式行为 | 阿里替代 |
|---------------|--------------|----------|
| LLM 固定文案 / fakeStream | 抛 `ALIBABA_LLM_NOT_CONFIGURED` | DashScope ChatClient |
| keyword 检索降级 | 禁用 | DashVector + text-embedding-v4 |
| RAG 本地 snippet 回答 | 抛 `ALIBABA_LLM_UNAVAILABLE` | DashScope generate |
| 跳过向量 indexing | 抛 `ALIBABA_VECTOR_STORE_NOT_CONFIGURED` | DashVector upsert |
| Tool Chat rule-based | 抛 `ALIBABA_TOOL_CHAT_UNAVAILABLE` | DashScope LLM Tool Calling |
| Workflow LLM 模板回答 | 抛 `ALIBABA_LLM_UNAVAILABLE` | DashScope generate |

关键环境变量：

```bash
DEMO_ALIBABA_STRICT_MODE=true
DEMO_AI_FALLBACK_ENABLED=false
DEMO_RAG_KEYWORD_FALLBACK_ENABLED=false
DEMO_RAG_RETRIEVER=dashvector
```

`GET /api/health` 返回 `embeddingConfigured`、`vectorStoreConfigured`、`ragRetriever`、`strictMode`、`fallbackEnabled`、`keywordFallbackEnabled`、`workflowRuntime`、`workflowRequirePublishedForRun` 等字段，便于确认当前是否走阿里栈与 workflow runtime。

本地 dev 可通过 `DEMO_ALIBABA_STRICT_MODE=false` 关闭严格模式（`application-alibaba-strict.yml` 已改为读取该 env）。CI/单测请设 `DEMO_ALIBABA_STRICT_MODE=false` 且 `DEMO_AI_FALLBACK_ENABLED=true`，或使用 `spring.profiles.group.dev=dev` 去掉 profile group 中的 `alibaba-strict`。

如果使用类似 DashScope `MultiModalConversation` 的专属 MaaS endpoint，例如 `qwen3.7-plus`，需要额外配置 base URL 和 completion path：

```bash
export AI_DASHSCOPE_CHAT_MODEL=qwen3.7-plus
export AI_DASHSCOPE_BASE_URL=https://your-maas-endpoint.cn-beijing.maas.aliyuncs.com/api/v1
export AI_DASHSCOPE_CHAT_COMPLETIONS_PATH=/services/aigc/multimodal-generation/generation
```

`AI_DASHSCOPE_BASE_URL` 支持两种写法：

- `https://your-maas-endpoint.cn-beijing.maas.aliyuncs.com`
- `https://your-maas-endpoint.cn-beijing.maas.aliyuncs.com/api/v1`

如果 base URL 已经带 `/api/v1`，completion path 可以写成 `/services/aigc/multimodal-generation/generation`，避免重复拼出 `/api/v1/api/v1`。

## DashVector RAG

DashVector RAG 需要同时配置 DashScope embedding 和 DashVector 连接信息。以下值只作为占位示例；不要提交真实 DashVector 或 DashScope key。

```bash
export AI_DASHSCOPE_EMBEDDING_MODEL=text-embedding-v4
export AI_DASHSCOPE_EMBEDDING_DIMENSION=1024
export AI_DASHSCOPE_EMBEDDING_BASE_URL=
export DASHVECTOR_ENDPOINT=vrs-cn-ln34u9ivx0001j.dashvector.cn-shanghai.aliyuncs.com
export DASHVECTOR_API_KEY=your-dashvector-api-key
export DASHVECTOR_COLLECTION=agent_rag_docs
export DASHVECTOR_DIMENSION=1024
export DASHVECTOR_METRIC=cosine
```

`AI_DASHSCOPE_BASE_URL` 只用于 Chat。`AI_DASHSCOPE_EMBEDDING_BASE_URL` 默认留空，让 embedding 使用 DashScope SDK 默认地址；只有 embedding 也需要专属 endpoint 时才设置它。

RAG 文档写入时，PostgreSQL 保存 source documents 和 chunk metadata，DashVector 保存向量。`DEMO_RAG_RETRIEVER=dashvector` 且 DashVector 已配置时使用向量检索；否则在非 strict 场景下降级为 naive keyword retrieval。向量检索失败时，若 `DEMO_RAG_KEYWORD_FALLBACK_ENABLED=true` 且未开启 strict，会回退到 keyword retrieval 并在 trace 中记录 `rag_keyword_fallback_retrieve`；strict 或 `DEMO_AI_FALLBACK_ENABLED=false` 时禁用该降级。

每个文档带有 `indexStatus`（保存与列表响应都会返回）：

- **PENDING**：chunk 已写入 PostgreSQL，向量写入 DashVector 的任务已入 outbox，尚未完成。
- **READY**：向量已写入 DashVector；无 DashVector 的 keyword-only 部署下文档保存即为 READY（无需向量）。
- **FAILED**：向量写入重试耗尽，文档内容仍在库中。
- **DELETING / DELETED**：删除处理中 / 已删除。

向量索引与删除通过 outbox 异步落到 DashVector（最终一致），不再与 HTTP 请求同步提交。Keyword 检索只要文档内容已持久化即可命中（PENDING/READY/FAILED 都可检索，仅排除 DELETING/DELETED），因此本地 keyword 降级在 DashVector 缺失时也能立即检索到新文档。阿里栈必填（strict）时若 DashVector 未配置仍会在写入/删除入口直接拒绝（`ALIBABA_VECTOR_STORE_NOT_CONFIGURED`）。

## Workflow Runtime

dev profile 默认组合 `workflow-graph`，即 **Graph Workflow runtime**（Spring AI Alibaba `StateGraph`）。如需回退到顺序执行器，设置：

```bash
export DEMO_WORKFLOW_RUNTIME=simple
```

默认 workflow runtime 是 `simple`（非 dev profile 或未加载 `workflow-graph` 时），也可以显式切换到 graph：

```bash
export DEMO_WORKFLOW_RUNTIME=graph
export DEMO_WORKFLOW_REQUIRE_PUBLISHED_FOR_RUN=true
export DEMO_CHAT_MEMORY_MAX_MESSAGES=20
```

`graph` runtime 会把当前 DSL 编译成 Spring AI Alibaba `StateGraph` / `CompiledGraph`，节点内部仍复用现有 `RagService`、`AiModelService` 和 `ToolGatewayService`，并继续写入统一 `run_step`。受限 `parallel` / `join` 会在 Graph 外层表达为 `parallel -> branch task -> join`；每个 branch task 内部按 DSL 顺序执行原分支节点，因此多步分支仍能保留原节点级 trace。

默认情况下，按已保存 `definitionId` 运行 workflow 需要 definition 处于 `PUBLISHED` 状态（可通过 `DEMO_WORKFLOW_REQUIRE_PUBLISHED_FOR_RUN=false` 关闭）。inline `workflowDefinition` 不受此约束。

## Conversation Memory

Chat、RAG Chat、Tool Chat 均支持 `conversationId`。未传时会自动生成 UUID，并在 PostgreSQL 表 `conversation_messages` 中持久化最近 N 轮 USER/ASSISTANT 消息（默认 N=20，可通过 `DEMO_CHAT_MEMORY_MAX_MESSAGES` 调整）。每次 append 后会自动裁剪超出上限的旧消息。后续请求会携带历史上下文调用 DashScope。

## MCP Tool Gateway

当前已增加统一工具网关：

- `ToolService`: 只保留本地工具实现，例如 `getCurrentTime` 和安全四则运算 `calculate`。
- `LocalToolProvider`: 把本地工具注册到统一网关。
- `McpToolProvider`: 读取 Spring AI MCP client 暴露的 `ToolCallbackProvider`，把远程 MCP tools 注册到统一网关。
- `ToolGatewayService`: agent 和 workflow 的统一工具调用入口。

`GET /api/tools` 会返回本地和远程工具列表。远程工具会带上 `inputSchema`，执行前会做最小服务端校验：`required` 参数必须存在，常见 JSON Schema 类型如 `string`、`number`、`integer`、`boolean`、`object`、`array` 会做基础匹配，并支持 `enum`、`anyOf`、`oneOf` 这类常见约束。

远程工具调用结果会进入 `run_step.outputJson`，包含 `provider`、`remote`、`serverName`、`durationMs`、`errorCategory` 和 `errorType`。`serverName` 由 `demo.mcp.server-name` 配置；例如 `mcp-github` profile 会写入 `github`。`errorType=NORMAL` 表示本地策略、参数或序列化错误，`errorType=RAW_REMOTE` 表示远程 MCP tool callback 抛出的原始调用错误。

`GET /api/tools/mcp/servers` 会返回 MCP server registry 的只读摘要，包括 server 名称、连接名、transport、toolsets、allowlist、已注册工具和必需环境变量是否已配置。它只暴露环境变量名称和布尔状态，不返回任何 token 或 secret 值。

当前 registry 是平台层配置视图。远程 tool 的运行时 `serverName` 仍由 `demo.mcp.server-name` 统一标记；如果同时接入多个 MCP server，后续需要在 provider 层补充更精确的 tool-to-connection 映射。

MCP client 默认关闭，不会在本地启动时主动连接远程 MCP server：

```bash
export DEMO_MCP_ENABLED=false
```

配置远程 MCP server 后再开启：

```bash
export DEMO_MCP_ENABLED=true
export DEMO_MCP_TOOLCALLBACK_ENABLED=true
```

`DEMO_MCP_ANNOTATION_SCANNER_ENABLED` 默认是 `false`。当前 demo 不使用 MCP 注解扫描，只有后续需要扫描本地 `@Mcp*` 注解时再开启。

远程 MCP tools 默认不允许执行，需要显式配置 allowlist：

```bash
export DEMO_TOOLS_ALLOWED_REMOTE_TOOLS=remote_echo,another_remote_tool
```

只在完全可信的本地 demo 环境中才建议打开全部远程工具：

```bash
export DEMO_TOOLS_ALLOW_ALL_REMOTE_TOOLS=true
```

远程 server 连接使用 Spring AI MCP client 标准配置前缀：

- `spring.ai.mcp.client.stdio.connections.*`
- `spring.ai.mcp.client.sse.connections.*`
- `spring.ai.mcp.client.streamable-http.connections.*`

建议把具体远程 MCP server 地址、命令或鉴权信息放在本地 `application-dev.yml` 或环境变量中，不要提交真实密钥。当前 demo 提供 `mcp-github` profile 作为本机 GitHub MCP server 示例。

## LLM Tool Calling Agent

`POST /api/agent/tool-chat` 在配置了 DashScope API Key 且 `ChatClient` 可用时，会通过 Spring AI 原生 tool-calling 循环调用本地与 MCP 工具；每次工具执行由 `TracingToolCallback` 写入 `run_step`。未配置模型时自动降级为规则匹配 + `AiModelService.generate` fallback，仍保留 trace 与 `conversationId` 行为。MCP 启用时，远程工具 callbacks 会合并进 LLM tool loop。

## 启动方式

应用有两种启动模式。

### 1. 快速演示（默认，无需 PostgreSQL）

不激活任何 profile 时使用内存 H2，Hibernate 自动建表，开箱即用：

```bash
./mvnw spring-boot:run
```

`/api/**` 默认开启 JWT 鉴权（仅 `/api/health` 与 `/api/auth/dev-token` 公开）。内置工作台在加载时
会自动向 `/api/auth/dev-token` 申请一个短期开发令牌并附加到所有请求与 SSE 调用，因此本地直接可用。
HS256 secret 未设置时使用内置的不安全默认值，仅供本机演示，任何共享环境务必通过
`DEMO_SECURITY_JWT_SECRET` 覆盖或改用 issuer 模式。

### 2. 全栈 dev profile（PostgreSQL + 阿里严格模式 + Graph runtime）

`dev` profile 组合 `alibaba-strict,postgres,workflow-graph`，需要 PostgreSQL。先启动数据库，任选其一：

**Docker Compose（推荐）**

```bash
docker compose up -d
```

**本机 PostgreSQL**

在 `.env` 中配置 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USERNAME`、`DB_PASSWORD`（见 `.env.example`），并确保库与用户已创建。

然后以 dev profile 启动应用：

```bash
./mvnw clean package
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

如果本机已安装全局 Maven，也可以使用 `mvn clean package` 和 `mvn spring-boot:run -Dspring-boot.run.profiles=dev`。

> **生产鉴权**：设 `DEMO_SECURITY_JWT_MODE=issuer` 并配置
> `spring.security.oauth2.resourceserver.jwt.issuer-uri`（或 `jwk-set-uri`），用标准 OIDC 取代内置
> HS256；同时设 `DEMO_SECURITY_DEV_TOKEN_ENABLED=false` 关闭开发令牌端点，由前置 IdP/BFF 接管工作台登录。
> 在 `prod` profile 下若仍在使用内置默认 secret，应用会拒绝启动。

默认地址：

```text
http://localhost:8080
```

## Frontend Workbench

启动后直接打开：

```text
http://localhost:8080/
```

这个页面由 Spring Boot 静态资源直接托管，不需要 Node.js、npm、Vite、React 或额外前端构建步骤。

当前工作台包含：

- Workflow canvas：节点 palette、拖拽节点、连线、节点配置 inspector、edge 编辑；**Insert Loop** 一键插入标准 loop 拓扑；subgraph 节点可通过下拉选择已保存 definition 与 revision。
- Workflow execution：调用 `/api/workflows/validate`、`/api/workflows/preview-graph`、`/api/workflows/run`，并读取 run graph 和 run steps；graph 不可用时 trace 仍会对 subgraph/dynamic/loop/parallel 做启发式分组。
- Definition 管理：保存、更新、加载、**Publish**、**Revisions 列表 / Rollback / Load revision**、当前 definition 的 **运行历史**（点击加载 trace）。
- 画布布局：节点坐标保存在浏览器 **localStorage**（按 definitionId 或 draft），刷新页面后布局保留。
- Chat：同步 `/api/chat` 与 SSE **Stream** `/api/chat/stream`。
- Agent：**Tool Chat** `/api/agent/tool-chat`（DashScope LLM tool calling）。
- RAG：文档保存/列表/问答；侧边栏 health 显示 `indexedDocumentCount`，重启后无文档时会提示重新索引。
- Tools / Runs：工具注册表、MCP 状态提示、run trace 列表。
- Runtime 侧栏：展示 `strictMode`、`fallbackEnabled`、`ragRetriever`、向量/embedding 就绪状态，以及 **`workflowRuntime`**（`simple` / `graph`）与 publish guard。

当前限制：

- MCP 远程工具仍需本地配置 `DEMO_MCP_ENABLED=true` 与 Spring AI MCP client 连接，工作台只展示状态，不会自动启用。
- 画布坐标是浏览器端 UI 状态，不写入后端 workflow definition；后端持久化的 executable DSL 仍然只有 `nodes` + `edges`。
- 文档元数据与会话记忆已持久化到 PostgreSQL；重启应用后仍保留。若 DashVector 与 DB 不同步（例如手动删库），需重新 Save Document。
- 画布是最小可用版本，不包含多人协作、权限、发布环境、节点市场或完整 Dify 应用管理。

## API 列表

- `GET /api/health`
- `POST /api/chat`
- `POST /api/chat/stream`
- `POST /api/agent/tool-chat`
- `GET /api/tools`
- `GET /api/tools/mcp/servers`
- `GET /api/rag/documents?page=0&size=20`
- `GET /api/rag/documents/{documentId}`
- `POST /api/rag/documents`
- `DELETE /api/rag/documents/{documentId}`
- `POST /api/rag/chat`
- `POST /api/workflows/definitions`
- `GET /api/workflows/definitions`
- `GET /api/workflows/definitions/{definitionId}`
- `GET /api/workflows/definitions/{definitionId}/revisions`
- `PUT /api/workflows/definitions/{definitionId}`
- `POST /api/workflows/definitions/{definitionId}/publish`
- `POST /api/workflows/definitions/{definitionId}/rollback/{version}`
- `DELETE /api/workflows/definitions/{definitionId}`
- `GET /api/workflows/node-schemas`
- `POST /api/workflows/validate`
- `POST /api/workflows/preview-graph`
- `POST /api/workflows/run`
- `GET /api/workflows/runs?definitionId={definitionId}&definitionVersion={version}&status={status}&page=0&size=20`
- `GET /api/workflows/runs/{runId}`
- `GET /api/workflows/runs/{runId}/graph`
- `GET /api/runs?type={type}&status={status}&page=0&size=20`
- `GET /api/runs/{runId}`
- `GET /api/runs/{runId}/steps`

除 SSE 外，所有 API 都返回 `ApiResponse`。

SSE 使用 `SseEmitter`，默认 timeout 为 `120000` ms，可通过 `demo.sse.timeout-ms` 调整。流式接口会发送 `message`、`done` 和 `error` 事件。

## curl 示例

健康检查：

```bash
curl http://localhost:8080/api/health
```

普通 Chat：

```bash
curl -X POST http://localhost:8080/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo-1","message":"Hello, introduce this backend in one sentence."}'
```

SSE 流式 Chat：

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"demo-1","message":"Stream a short answer."}'
```

Tool Calling：

```bash
curl -X POST http://localhost:8080/api/agent/tool-chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"tool-1","message":"Please calculate (12 + 8) / 5"}'
```

查看已注册工具：

```bash
curl http://localhost:8080/api/tools
```

查看 MCP server registry 摘要：

```bash
curl http://localhost:8080/api/tools/mcp/servers
```

提交 RAG 文档：

```bash
curl -X POST http://localhost:8080/api/rag/documents \
  -H 'Content-Type: application/json' \
  -d '{"title":"Demo Doc","content":"Spring AI Alibaba can build Java AI agents with DashScope, tools, RAG and graph workflows."}'
```

RAG 问答：

```bash
curl -X POST http://localhost:8080/api/rag/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"rag-1","message":"What can Spring AI Alibaba build?"}'
```

删除 RAG 文档（先删 PostgreSQL 中的 document/chunk，再在 DashVector 已配置时清理向量；向量清理失败不会回滚 DB 删除）：

```bash
curl -X DELETE http://localhost:8080/api/rag/documents/1
```

Workflow DSL 运行：

```bash
curl -X POST http://localhost:8080/api/workflows/run \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {"id": "retriever_1", "type": "retriever", "config": {"topK": 3}},
        {"id": "llm_1", "type": "llm", "config": {"prompt": "基于上下文回答：{{context}}\n用户问题：{{input}}"}},
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "retriever_1"},
        {"from": "retriever_1", "to": "llm_1"},
        {"from": "llm_1", "to": "end"}
      ]
    },
    "input": {
      "message": "What can Spring AI Alibaba build?"
    }
  }'
```

条件分支 Workflow：

```bash
curl -X POST http://localhost:8080/api/workflows/run \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {"id": "check_intent", "type": "condition", "config": {"left": "{{input.intent}}", "operator": "equals", "right": "time"}},
        {"id": "tool_time", "type": "tool", "config": {"toolName": "getCurrentTime"}},
        {"id": "llm_fallback", "type": "llm", "config": {"prompt": "回答用户问题：{{input}}"}},
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "check_intent"},
        {"from": "check_intent", "to": "tool_time", "condition": "true"},
        {"from": "check_intent", "to": "llm_fallback", "condition": "false"},
        {"from": "tool_time", "to": "end"},
        {"from": "llm_fallback", "to": "end"}
      ]
    },
    "input": {
      "message": "现在几点？",
      "intent": "time"
    }
  }'
```

带节点执行控制的 Workflow：

```bash
curl -X POST http://localhost:8080/api/workflows/run \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {
          "id": "tool_time",
          "type": "tool",
          "config": {
            "toolName": "getCurrentTime",
            "retryCount": 1,
            "timeoutMs": 2000
          }
        },
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "tool_time"},
        {"from": "tool_time", "to": "end"}
      ]
    },
    "input": {
      "message": "run tool node with execution controls"
    }
  }'
```

并行分支 Workflow：

```bash
curl -X POST http://localhost:8080/api/workflows/run \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {"id": "parallel_1", "type": "parallel", "config": {}},
        {"id": "time_a", "type": "tool", "config": {"toolName": "getCurrentTime"}},
        {"id": "time_b", "type": "tool", "config": {"toolName": "getCurrentTime"}},
        {"id": "join_1", "type": "join", "config": {}},
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "parallel_1"},
        {"from": "parallel_1", "to": "time_a"},
        {"from": "parallel_1", "to": "time_b"},
        {"from": "time_a", "to": "join_1"},
        {"from": "time_b", "to": "join_1"},
        {"from": "join_1", "to": "end"}
      ]
    },
    "input": {
      "message": "run two tool branches"
    }
  }'
```

保存 Workflow 定义：

```bash
curl -X POST http://localhost:8080/api/workflows/definitions \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "RAG Answer Workflow",
    "description": "Retrieve docs, then answer with Qwen.",
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {"id": "retriever_1", "type": "retriever", "config": {"topK": 3}},
        {"id": "llm_1", "type": "llm", "config": {"prompt": "基于上下文回答：{{context}}\n用户问题：{{input}}"}},
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "retriever_1"},
        {"from": "retriever_1", "to": "llm_1"},
        {"from": "llm_1", "to": "end"}
      ]
    }
  }'
```

查看已保存 Workflow 定义：

```bash
curl http://localhost:8080/api/workflows/definitions
curl http://localhost:8080/api/workflows/definitions/{definitionId}
```

查看 Workflow 定义 revision 历史：

```bash
curl http://localhost:8080/api/workflows/definitions/{definitionId}/revisions
```

更新 Workflow 定义：

```bash
curl -X PUT http://localhost:8080/api/workflows/definitions/{definitionId} \
  -H 'Content-Type: application/json' \
  -d '{
    "name": "RAG Answer Workflow v2",
    "description": "Retrieve docs, then answer with a revised prompt.",
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {"id": "retriever_1", "type": "retriever", "config": {"topK": 5}},
        {"id": "llm_1", "type": "llm", "config": {"prompt": "请基于上下文精简回答：{{context}}\n用户问题：{{input}}"}},
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "retriever_1"},
        {"from": "retriever_1", "to": "llm_1"},
        {"from": "llm_1", "to": "end"}
      ]
    }
  }'
```

发布 Workflow 定义：

```bash
curl -X POST http://localhost:8080/api/workflows/definitions/{definitionId}/publish
```

回滚 Workflow 定义：

```bash
curl -X POST http://localhost:8080/api/workflows/definitions/{definitionId}/rollback/1
```

回滚不会覆盖历史 revision；它会把目标 revision 的快照复制成一个新的 `DRAFT` 版本。

删除 Workflow 定义：

```bash
curl -X DELETE http://localhost:8080/api/workflows/definitions/{definitionId}
```

删除只允许用于还没有运行历史的 Workflow 定义。只要该定义已经写入过 `workflow_run_records`，接口会返回 `WORKFLOW_DEFINITION_IN_USE`，避免删除 definition 后破坏 run trace 审计链。

按已保存定义运行 Workflow：

```bash
curl -X POST http://localhost:8080/api/workflows/run \
  -H 'Content-Type: application/json' \
  -d '{
    "definitionId": "{definitionId}",
    "input": {
      "message": "What can Spring AI Alibaba build?"
    }
  }'
```

按指定 revision 运行 Workflow：

```bash
curl -X POST http://localhost:8080/api/workflows/run \
  -H 'Content-Type: application/json' \
  -d '{
    "definitionId": "{definitionId}",
    "definitionVersion": 1,
    "input": {
      "message": "Run against a pinned workflow revision."
    }
  }'
```

如果请求只传 `definitionId`，运行时会解析当前版本，并在响应与 run trace input 中记录实际使用的 `definitionId` / `definitionVersion`。如果同时传 `definitionVersion`，运行会固定到对应 revision，便于之后复现历史 run。

查看已保存 Workflow 定义的运行记录：

```bash
curl 'http://localhost:8080/api/workflows/runs?definitionId={definitionId}'
curl 'http://localhost:8080/api/workflows/runs?definitionId={definitionId}&definitionVersion=1'
curl 'http://localhost:8080/api/workflows/runs?definitionId={definitionId}&status=SUCCEEDED&page=0&size=20'
```

该接口返回分页结果：`content`、`page`、`size`、`totalElements`、`totalPages`。`content` 按 `startedAt` 倒序排列，元素包括 `runId`、`definitionId`、`definitionVersion`、`startedAt`、`status`、`output`、`errorMessage` 和 `endedAt`。`status` 可选值为 `RUNNING`、`SUCCEEDED`、`FAILED`。完整 run input 和节点步骤仍通过 `/api/runs/{runId}` 与 `/api/runs/{runId}/steps` 查询。

查看单个 Workflow run 聚合详情：

```bash
curl http://localhost:8080/api/workflows/runs/{runId}
```

该接口返回 `summary`、`run` 和 `steps`。`summary` 带 workflow definition/version 索引信息；`run` 和 `steps` 复用通用 trace 数据结构。

导出单个 Workflow run 图结构：

```bash
curl http://localhost:8080/api/workflows/runs/{runId}/graph
```

该接口会先读取 run trace，再解析 workflow 定义：已保存 workflow definition 触发的 run 会通过 `workflow_run_records` 定位当次运行使用的 definition revision；inline workflow run 会从 run trace input 中恢复当次提交的 `workflowDefinition`。随后接口会叠加 `/api/runs/{runId}/steps` 中的节点执行状态，返回 `summary`、`nodes`、`edges` 和 `mermaid`。`nodes` 会标记 `executed`、`status`、`stepId` 和 `errorMessage`；`edges` 会标记是否 `traversed`，未执行分支会用虚线 Mermaid edge 表示。它不会执行节点、不会调用模型/RAG/工具，也不会创建新的 run trace。

如果某个 workflow run 只保存了 `definitionId`，但对应的 `workflow_run_records` 元数据已经丢失，接口无法可靠恢复当次 revision，会返回 `WORKFLOW_GRAPH_UNAVAILABLE`。这类 run 仍然可以通过 `/api/runs/{runId}` 和 `/api/runs/{runId}/steps` 查看原始 trace。

查看 Workflow 节点 schema：

```bash
curl http://localhost:8080/api/workflows/node-schemas
```

校验 Workflow 定义：

```bash
curl -X POST http://localhost:8080/api/workflows/validate \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {"id": "tool_time", "type": "tool", "config": {"toolName": "getCurrentTime"}},
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "tool_time"},
        {"from": "tool_time", "to": "end"}
      ]
    }
  }'
```

该接口只做 DSL schema 和拓扑编译校验，不执行模型、RAG 或工具，也不会创建 run trace。有效定义返回 `valid=true` 和摘要；无效定义返回 `valid=false` 与第一条编译错误。

预览 Workflow 图结构：

```bash
curl -X POST http://localhost:8080/api/workflows/preview-graph \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {"id": "retriever_1", "type": "retriever", "config": {"topK": 3}},
        {"id": "llm_1", "type": "llm", "config": {"prompt": "基于上下文回答：{{context}}\n用户问题：{{input}}"}},
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "retriever_1"},
        {"from": "retriever_1", "to": "llm_1"},
        {"from": "llm_1", "to": "end"}
      ]
    }
  }'
```

该接口会复用 `WorkflowCompiler` 校验 DSL，并返回 `summary`、有序 `nodes`、有序 `edges` 和 `mermaid` 文本，方便后续可视化画布或文档预览使用。它不会执行节点、不会调用模型/RAG/工具，也不会创建 run trace。

查看运行轨迹：

```bash
curl http://localhost:8080/api/runs
curl http://localhost:8080/api/runs/{runId}/steps
```

## Workflow DSL

当前 workflow 是平台层最小 DSL + runtime，配套 **Dify-like 工作台画布**（静态 HTML/JS）。核心结构：

- `WorkflowDefinition`: `nodes` + `edges`
- `WorkflowNode`: `id`、`type`、`config`
- `WorkflowEdge`: `from`、`to`，条件分支边可增加 `condition: "true" | "false"`
- `WorkflowRunRequest`: `workflowDefinition` + `input`，或 `definitionId` + 可选 `definitionVersion` + `input`
- `WorkflowDefinitionStatus`: `DRAFT` / `PUBLISHED`
- `WorkflowDefinitionRevision`: 每次新建和更新都会保存一条定义快照，便于后续回滚和审计。
- `WorkflowRunRecord`: 按 `definitionId` / `definitionVersion` 索引已保存定义触发的 workflow run。
- `WorkflowValidationRequest`: 画布或 DSL 编辑器保存前的编译级校验请求。
- `WorkflowGraphPreviewRequest`: 画布或 DSL 编辑器的图结构预览请求，返回节点、边和 Mermaid 文本，不执行 workflow。
- `WorkflowRunGraphResponse`: 已执行 workflow run 的图结构响应，返回定义节点/边、trace 执行状态和 Mermaid 文本。
- `WorkflowRuntime`: runtime 抽象，当前支持 `simple` 和 `graph`
- `SimpleWorkflowRuntime`: 执行线性节点、`condition` true/false 分支，以及受限 `parallel` / `join` 并行合流
- `GraphWorkflowRuntime`: 使用 Spring AI Alibaba `StateGraph` / `CompiledGraph` 执行线性节点、`condition` true/false 条件分支，以及受限 `parallel` / `join` 并行合流
- `WorkflowNodeSchemaRegistry`: 返回当前支持节点的配置字段、默认值、约束和模板变量，供后续画布或 DSL 编辑器使用

`WorkflowCompiler` 会复用节点 schema 做基础 config 校验：不允许未知 config key，常见类型需要匹配，`retriever.topK` 会校验 `1..20` 范围，`retryCount` / `timeoutMs` 会校验执行控制范围，字符串配置如果显式传入则不能为空。

已支持节点类型：

- `start`: 将请求 `input` 放入 workflow 状态。
- `retriever`: 复用 `RagService.retrieve`，底层 retriever 由 `DEMO_RAG_RETRIEVER` 和 DashVector 配置决定，`config.topK` 默认 `3`，最大 `20`。
- `llm`: 复用 `AiModelService` 调用 DashScope/Qwen；无 `AI_DASHSCOPE_API_KEY` 时走 fallback。支持 workflow 变量。
- `tool`: 复用 `ToolGatewayService`，当前本地支持 `getCurrentTime` 和 `calculate`；开启 MCP 后可调用远程 MCP tool 名称。
- `condition`: 计算一个布尔条件，并从 `condition=true` 或 `condition=false` 的 outgoing edge 中选择下一节点。支持 `equals`、`notEquals`、`contains`、`notContains`、`startsWith`、`endsWith`、`exists`、`notExists`、`greaterThan`、`lessThan`。
- `parallel`: 受限并行分支入口，至少两条普通 outgoing edge，每条分支会并发执行。
- `join`: `parallel` 分支合流节点，输出 `branchOutputs`，key 为分支起始节点 id。
- `loop`: 受控 while 循环（`maxIterations` 1–50 + 条件字段）。outgoing 使用 `condition=body` / `condition=exit`；body 链必须以 `loop_back` 结束并指回 `loop`。
- `loop_back`: 循环体结束标记（编译期拓扑节点，由 `loop` 节点 inline 执行 body）。
- `subgraph`: 按 `definitionId`（+ 可选 `version`）加载已保存 workflow 并在**父 runId** 下 inline 运行；嵌套节点 trace 使用 `{subgraphNodeId}::{nestedNodeId}` 命名空间，便于 run graph 与 `listSteps` 归类。
- `dynamic`: 按 `itemsFrom` 模板解析列表，顺序执行 `action=tool`（demo 范围）。
- `end`: 输出最终 workflow 结果。

所有节点都支持通用执行控制字段：

- `retryCount`: 默认 `0`，范围 `0..5`。表示首轮失败后的最大重试次数，总尝试次数为 `retryCount + 1`。
- `timeoutMs`: 默认 `0`，范围 `0..300000`。表示每次尝试的超时时间，`0` 代表不启用超时。

当节点配置了 retry 或 timeout，`run_step.outputJson` 会把原始输出或错误包装为 `output`，并附带 `attempts`、`retryCount` 和 `timeoutMs`。`attempts` 记录每次尝试的 `attempt`、`status`、`durationMs`，失败尝试还会记录 `errorType` 和 `errorMessage`。

Workflow 变量第一版由 `WorkflowVariableResolver` 统一解析，当前支持：

- `{{input}}`: 主要用户输入，优先取 `input.message`，其次取 `input.query`。
- `{{input.field}}`: 读取请求 input 中的字段，支持多层 Map 路径和 List 下标，例如 `{{input.intent}}`、`{{input.items.0}}`。
- `{{context}}`: 当前 RAG retrieved context 的文本拼接。
- `{{lastOutput}}` / `{{lastOutput.field}}`: 上一个节点输出。
- `{{nodes.nodeId.field}}`: 指定节点的已记录输出，例如 `{{nodes.tool_first.text}}`。
- `{{toolResult}}`: 最近一次工具调用输出。
- `{{answer}}`: 最近一次 LLM 节点生成的答案。

当字符串完全等于一个变量模板时，解析器会保留原始类型，例如 `{"count": "{{input.count}}"}` 会把数字继续作为数字传给工具；普通插值如 `"count={{input.count}}"` 仍会渲染成字符串。

当前限制：

- 只支持一个 `start` 和一个 `end`。
- `simple` 和 `graph` runtime 都支持线性 DAG 和 `condition` 节点的 true/false 分支，可以在后续节点或 `end` 合流。
- `condition` 节点必须有且只有两条 outgoing edge，分别声明 `condition: "true"` 和 `condition: "false"`。
- `simple` runtime 支持受限 `parallel` / `join`：`parallel` 至少两条普通 outgoing edge，每个分支必须是线性节点链，并且必须汇入同一个 `join`。
- `parallel` 分支内部暂不支持嵌套 `condition`、嵌套 `parallel` 或 loop。
- 非 `condition` / `parallel` / `loop` 节点不能有多条 outgoing edge，复杂图会返回 `WORKFLOW_UNSUPPORTED`。
- `graph` runtime 支持与 `simple` runtime 相同的受限 `parallel` / `join` 拓扑。由于当前 Graph core 的原生 parallel edge 对多步分支收敛有限制，`GraphWorkflowRuntime` 会把每条分支编译成一个 synthetic branch task，branch task 内部继续按原 DSL 节点顺序执行并写入原节点的 `run_step`。
- `loop` / `subgraph` / `dynamic` 在 Graph runtime 中作为 composite opaque 节点执行（body 内仍保留节点级 trace）；尚未映射为 Spring AI Alibaba 原生 `StateGraph` 子图。
- 仅允许结构化 `loop_back -> loop` 回边；任意其他 cycle 仍会返回 `WORKFLOW_UNSUPPORTED`。
- 节点 timeout 是 per-attempt 控制，取消执行是 best-effort；底层模型客户端或远程工具如果不响应线程中断，可能在后台完成后才真正释放。
- 当前 retry 只做固定次数重试，还没有 backoff、熔断或按错误类型选择是否重试。
- Workflow 定义可保存到 PostgreSQL 的 `workflow_definitions` 表；新建为 `DRAFT` v1，更新时版本递增并回到 `DRAFT`，发布后状态为 `PUBLISHED`。
- 每次新建和更新都会写入 PostgreSQL 的 `workflow_definition_revisions` 表；发布时会同步当前版本 revision 的状态为 `PUBLISHED`。
- 回滚会从历史 revision 复制快照并生成新的 `DRAFT` 版本，不会覆盖旧 revision。
- 按已保存定义运行时会在响应和 run trace input 中记录实际使用的 `definitionId` / `definitionVersion`。
- 按已保存定义运行时会写入 PostgreSQL 的 `workflow_run_records` 表，便于按定义或 revision 查询历史 run；inline workflow 运行不会写入该索引。
- `GET /api/workflows/runs/{runId}/graph` 支持已保存定义触发的 run 和 inline workflow run；只传 `definitionId` 但缺少 `workflow_run_records` 元数据时会返回 `WORKFLOW_GRAPH_UNAVAILABLE`。
- Run graph 响应在 advanced 节点场景下会附带 composite 元数据：`compositeRole`（`LOOP` / `SUBGRAPH` / `DYNAMIC` / `LOOP_BACK` / `PARALLEL` / `JOIN`）、`parallelGroup`、`iterations`（loop）、`children`（inline 子步骤）。`dynamic` 合成 step（`{id}:dynamic:{index}:{tool}`）与 `subgraph` 命名空间 step（`{id}::{nestedId}`）会归入对应容器节点；`parallel` 会暴露 synthetic branch id（`workflow_branch_{parallel}_{branchStart}`）及分支内节点；`loop_back` 状态由所属 `loop` 推导。Mermaid 输出会为 parallel / loop / composite 容器生成 `subgraph` cluster。
- 删除 Workflow 定义时会先检查 `workflow_run_records`；已有运行历史的定义不能删除，没有运行历史的定义会连同 revision 快照一起删除。
- 当前还没有租户隔离或发布环境区分。
- 节点 schema registry 是只读内置列表，还不是数据库驱动的动态节点市场。
- 每个节点都会写入 `run_step`，整体 run type 为 `WORKFLOW`。

Spring AI Alibaba Graph 接入：

- 当前项目已接入 `spring-ai-alibaba-graph-core`，版本跟随 Spring AI Alibaba `1.1.2.2`。
- `WorkflowDefinition` 仍是产品层 DSL；`WorkflowCompiler` 会先输出平台层 execution plan。
- `GraphWorkflowRuntime` 会把普通边映射为 `StateGraph.addEdge(String, String)`，把 `condition` true/false 边映射为 `StateGraph.addConditionalEdges`。
- `parallel` fan-out 映射为 `StateGraph.addEdge(String, List<String>)`。分支入口会先映射成 synthetic branch task，branch task 内部执行原 DSL 分支节点；branch task 再通过普通边汇入 `join`。
- 当前 Graph 接入已经覆盖线性 DAG、最小条件分支和受限并行合流；后续要继续补原生子图映射、循环防护策略和更完整的状态合并策略。

可视化画布接入点：

- 前端画布只需要生成同样的 `nodes` / `edges` JSON。
- 后续可把节点 schema 固化为 registry：节点类型、配置表单、输入输出变量、校验规则。
- `GET /api/workflows/node-schemas` 可驱动画布节点面板和配置表单。
- `POST /api/workflows/validate` 可用于保存前校验，不会执行节点或产生 run trace。
- `POST /api/workflows/preview-graph` 可用于保存前图结构预览，返回后端规范化的节点、边和 Mermaid 文本，不会执行节点或产生 run trace。
- `GET /api/workflows/runs/{runId}/graph` 可用于运行后图结构检查，返回节点执行状态、composite children、未执行分支和 Mermaid 文本（advanced 图含 parallel / loop cluster）。
- 当前 API 已支持保存 workflow definition，并可作为画布保存前的运行预览接口。

## GitHub MCP 本地示例

可以用本机 GitHub CLI 登录态启动官方 GitHub MCP server。以下示例使用只读模式和 `repos` toolset，不会把 GitHub token 写入仓库。

先安装 GitHub MCP server，并把当前 GitHub CLI token 放到当前 shell 的临时环境变量里：

```bash
go install github.com/github/github-mcp-server/cmd/github-mcp-server@latest

export GITHUB_PERSONAL_ACCESS_TOKEN="$(gh auth token)"
export GITHUB_MCP_SERVER_COMMAND="$(go env GOPATH)/bin/github-mcp-server"
export DEMO_MCP_SERVER_NAME=github
export DEMO_TOOLS_ALLOWED_REMOTE_TOOLS=github:get_file_contents,github:search_repositories,github:list_commits,github:get_commit,github:list_branches

./mvnw spring-boot:run -Dspring-boot.run.profiles=dev,mcp-github
```

`mcp-github` profile 定义在 `src/main/resources/application-mcp-github.yml`。如果以后接多个 MCP server，可以复制该 profile 的 `spring.ai.mcp.client.stdio.connections.github` 配置块，改成新的连接名和命令，并在 `demo.mcp.registry.servers` 增加对应 server 摘要，把允许执行的远程工具加入 `DEMO_TOOLS_ALLOWED_REMOTE_TOOLS`。

启动后可以先查看 GitHub MCP 暴露的工具名：

```bash
curl http://localhost:8080/api/tools
curl http://localhost:8080/api/tools/mcp/servers
```

用 workflow 调用远程 GitHub MCP 工具读取当前仓库 README：

```bash
curl -X POST http://localhost:8080/api/workflows/run \
  -H 'Content-Type: application/json' \
  -d '{
    "workflowDefinition": {
      "nodes": [
        {"id": "start", "type": "start", "config": {}},
        {
          "id": "github_file",
          "type": "tool",
          "config": {
            "toolName": "get_file_contents",
            "arguments": {
              "owner": "Jerry2003826",
              "repo": "jiaruialibaba",
              "path": "README.md",
              "branch": "main"
            }
          }
        },
        {"id": "end", "type": "end", "config": {}}
      ],
      "edges": [
        {"from": "start", "to": "github_file"},
        {"from": "github_file", "to": "end"}
      ]
    },
    "input": {
      "message": "read README through GitHub MCP"
    }
  }'
```

## PostgreSQL

dev 使用 `application-postgres.yml`，默认连接：

```text
jdbc:postgresql://localhost:5432/agent_demo
User: agent_demo
Password: agent_demo
```

可用 `psql` 或任意 PostgreSQL 客户端查看 `rag_documents`、`conversation_messages`、`workflow_definitions` 等表。单测通过 `src/test/resources/application.properties` 隔离为 H2，不依赖本地 Postgres。

### 数据库迁移（Flyway）

`postgres` profile 下 schema 由 Flyway 管理、Hibernate 仅做 `validate`：

- 迁移脚本在 `src/main/resources/db/migration/`：`V1__baseline_schema.sql`（基线表结构）、`V2__index_status_row_version_outbox.sql`（新增 `rag_documents.index_status`、`workflow_definitions.row_version`、`vector_outbox_events` 表与 claim 索引，采用 expand-contract：先加可空列、回填、再置 NOT NULL）。
- 配置见 `application-postgres.yml`：`baseline-on-migrate: true`、`baseline-version: 1`。全新库会依次执行 V1+V2；已有（未接入 Flyway 的）库会被标记为 V1 基线，仅执行 V2 起的增量。
- H2 默认演示与单测不走 Flyway（`spring.flyway.enabled: false`），由 Hibernate `ddl-auto` 直接建表。
- 真实 Postgres 上的「迁移 + validate」由 `PostgresFlywayMigrationIntegrationTest`（Testcontainers）在 CI 验证；无 Docker 环境会自动跳过。

> **遗留库注意**：大文本/JSON 列（`content`、`*_json`、`input`/`output` 等）现在映射为 `text`。若某个 Postgres 库是更早版本用 Hibernate `ddl-auto` 自动建表的，这些列可能是 `oid`（大对象），采用本套迁移前需要先做一次性的 `oid → text` 数据转换（与具体部署相关，未自动化）。

## 后续扩展方向

- 当前已接入 DashVector + DashScope `EmbeddingModel`；后续可继续替换为 Spring AI `VectorStore` 标准抽象或增加 hybrid retrieval / rerank。
- 扩展 Spring AI Alibaba Graph runtime：原生子图映射、循环防护策略、更完整的状态合并策略。
- 扩展 MCP：增加鉴权配置、工具 schema 同步、调用审计和熔断策略。
- 接入 Cairn Memory，为 coding-agent 或长会话场景补充行为记忆和连续性。
- 扩展 Dify-like 工作台：持久化画布坐标、增加应用发布、节点市场、数据集管理、租户权限和生产级可视化编辑器。
