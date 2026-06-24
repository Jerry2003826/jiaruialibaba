# Spring AI Alibaba Agent Backend Demo

## 项目目标

这是一个最小可运行、可扩展的 Spring AI Alibaba 后端骨架。它不是完整 Dify，而是为后续扩展成 Dify-like 平台预留基础能力：Chat、SSE streaming、tool calling、简单 RAG、run trace。

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
- H2 in-memory database

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

`AI_DASHSCOPE_API_KEY` 不配置时，服务仍可启动，并会对 Chat/SSE/RAG 使用 fallback 响应，方便本地验证接口和 trace。
为了兼容 DashScope Python SDK 的常见命名，项目也支持 `DASHSCOPE_API_KEY` 作为备用读取来源；推荐在本项目中继续使用 `AI_DASHSCOPE_API_KEY`。

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

RAG 文档写入时，H2 保存 source documents 和 chunk metadata，DashVector 保存向量。当前 retriever 可通过 `DEMO_RAG_RETRIEVER` 选择；设置为 `dashvector` 且 DashVector 已配置时使用向量检索，否则使用 naive keyword retrieval。向量检索失败时，如果 `DEMO_RAG_KEYWORD_FALLBACK_ENABLED=true`，会回退到 naive keyword retrieval，并在 run trace 中记录 `rag_keyword_fallback_retrieve` 步骤。

## Workflow Runtime

默认 workflow runtime 是 `simple`，可以切换到 Spring AI Alibaba Graph runtime：

```bash
export DEMO_WORKFLOW_RUNTIME=graph
```

`graph` runtime 会把当前线性 DSL 编译成 Spring AI Alibaba `StateGraph` / `CompiledGraph`，节点内部仍复用现有 `RagService`、`AiModelService` 和 `ToolGatewayService`，并继续写入统一 `run_step`。

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

## 启动方式

```bash
./mvnw clean package
./mvnw spring-boot:run
```

如果本机已安装全局 Maven，也可以使用 `mvn clean package` 和 `mvn spring-boot:run`。

默认地址：

```text
http://localhost:8080
```

## API 列表

- `GET /api/health`
- `POST /api/chat`
- `POST /api/chat/stream`
- `POST /api/agent/tool-chat`
- `GET /api/tools`
- `GET /api/tools/mcp/servers`
- `POST /api/rag/documents`
- `POST /api/rag/chat`
- `POST /api/workflows/definitions`
- `GET /api/workflows/definitions`
- `GET /api/workflows/definitions/{definitionId}`
- `GET /api/workflows/node-schemas`
- `POST /api/workflows/run`
- `GET /api/runs`
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

查看 Workflow 节点 schema：

```bash
curl http://localhost:8080/api/workflows/node-schemas
```

查看运行轨迹：

```bash
curl http://localhost:8080/api/runs
curl http://localhost:8080/api/runs/{runId}/steps
```

## Workflow DSL

当前 workflow 是平台层最小 DSL + runtime，不是完整 Dify，也没有前端画布。核心结构：

- `WorkflowDefinition`: `nodes` + `edges`
- `WorkflowNode`: `id`、`type`、`config`
- `WorkflowEdge`: `from`、`to`
- `WorkflowRunRequest`: `workflowDefinition` + `input`，或 `definitionId` + `input`
- `WorkflowRuntime`: runtime 抽象，当前支持 `simple` 和 `graph`
- `SimpleWorkflowRuntime`: 直接按线性节点顺序执行
- `GraphWorkflowRuntime`: 使用 Spring AI Alibaba `StateGraph` / `CompiledGraph` 执行同一组线性节点
- `WorkflowNodeSchemaRegistry`: 返回当前支持节点的配置字段、默认值、约束和模板变量，供后续画布或 DSL 编辑器使用

`WorkflowCompiler` 会复用节点 schema 做基础 config 校验：不允许未知 config key，常见类型需要匹配，`retriever.topK` 会校验 `1..20` 范围，字符串配置如果显式传入则不能为空。

已支持节点类型：

- `start`: 将请求 `input` 放入 workflow 状态。
- `retriever`: 复用 `RagService.retrieve`，底层 retriever 由 `DEMO_RAG_RETRIEVER` 和 DashVector 配置决定，`config.topK` 默认 `3`，最大 `20`。
- `llm`: 复用 `AiModelService` 调用 DashScope/Qwen；无 `AI_DASHSCOPE_API_KEY` 时走 fallback。支持模板变量 `{{input}}`、`{{context}}`、`{{lastOutput}}`、`{{toolResult}}`。
- `tool`: 复用 `ToolGatewayService`，当前本地支持 `getCurrentTime` 和 `calculate`；开启 MCP 后可调用远程 MCP tool 名称。
- `end`: 输出最终 workflow 结果。

当前限制：

- 只支持一个 `start` 和一个 `end`。
- 只支持线性 DAG：不支持分支、合流、并行、循环、条件边。
- 复杂图会返回 `WORKFLOW_UNSUPPORTED`。
- Workflow 定义可保存到 H2 的 `workflow_definitions` 表；当前没有版本管理、发布状态或租户隔离。
- 节点 schema registry 是只读内置列表，还不是数据库驱动的动态节点市场。
- 每个节点都会写入 `run_step`，整体 run type 为 `WORKFLOW`。

Spring AI Alibaba Graph 接入：

- 当前项目已接入 `spring-ai-alibaba-graph-core`，版本跟随 Spring AI Alibaba `1.1.2.2`。
- `WorkflowDefinition` 仍是产品层 DSL；`WorkflowCompiler` 输出线性节点列表，由 `GraphWorkflowRuntime` 编译成 `StateGraph`。
- 第一版只把线性 DAG 映射到 Graph。分支、合流、并行、循环和条件边仍由 `WorkflowCompiler` 拒绝。

可视化画布接入点：

- 前端画布只需要生成同样的 `nodes` / `edges` JSON。
- 后续可把节点 schema 固化为 registry：节点类型、配置表单、输入输出变量、校验规则。
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

## H2 Console

```text
http://localhost:8080/h2-console
```

连接信息：

```text
JDBC URL: jdbc:h2:mem:agent_demo
User Name: sa
Password:
```

## 后续扩展方向

- 当前已接入 DashVector + DashScope `EmbeddingModel`；后续可继续替换为 Spring AI `VectorStore` 标准抽象或增加 hybrid retrieval / rerank。
- 扩展 Spring AI Alibaba Graph runtime：条件边、并行节点、子图、持久化 workflow definition。
- 扩展 MCP：增加鉴权配置、工具 schema 同步、调用审计和熔断策略。
- 接入 Cairn Memory，为 coding-agent 或长会话场景补充行为记忆和连续性。
- 扩展成 Dify-like Workflow DSL：节点、边、变量、条件分支、工具节点、RAG 节点、trace 可视化。
