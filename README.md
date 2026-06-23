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
export DASHVECTOR_ENDPOINT=vrs-cn-ln34u9ivx0001j.dashvector.cn-shanghai.aliyuncs.com
export DASHVECTOR_API_KEY=your-dashvector-api-key
export DASHVECTOR_COLLECTION=agent_rag_docs
export DASHVECTOR_DIMENSION=1024
export DASHVECTOR_METRIC=cosine
```

RAG 文档写入时，H2 保存 source documents 和 chunk metadata，DashVector 保存向量。当前 retriever 可通过 `DEMO_RAG_RETRIEVER` 选择；设置为 `dashvector` 且 DashVector 已配置时使用向量检索，否则使用 naive keyword retrieval。向量检索失败时，如果 `DEMO_RAG_KEYWORD_FALLBACK_ENABLED=true`，会回退到 naive keyword retrieval，并在 run trace 中记录 `rag_keyword_fallback_retrieve` 步骤。

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
- `POST /api/rag/documents`
- `POST /api/rag/chat`
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
- `WorkflowRunRequest`: `workflowDefinition` + `input`
- `SimpleWorkflowRuntime`: 当前线性 runtime，后续可替换为 Spring AI Alibaba Graph Runtime

已支持节点类型：

- `start`: 将请求 `input` 放入 workflow 状态。
- `retriever`: 复用 `RagService.retrieve`，底层 retriever 由 `DEMO_RAG_RETRIEVER` 和 DashVector 配置决定，`config.topK` 默认 `3`，最大 `20`。
- `llm`: 复用 `AiModelService` 调用 DashScope/Qwen；无 `AI_DASHSCOPE_API_KEY` 时走 fallback。支持模板变量 `{{input}}`、`{{context}}`、`{{lastOutput}}`、`{{toolResult}}`。
- `tool`: 复用 `ToolService`，当前支持 `getCurrentTime` 和 `calculate`。
- `end`: 输出最终 workflow 结果。

当前限制：

- 只支持一个 `start` 和一个 `end`。
- 只支持线性 DAG：不支持分支、合流、并行、循环、条件边。
- 复杂图会返回 `WORKFLOW_UNSUPPORTED`。
- Workflow 定义暂不持久化，只随请求提交并立即运行。
- 每个节点都会写入 `run_step`，整体 run type 为 `WORKFLOW`。

Spring AI Alibaba Graph 接入点：

- 当前项目未直接引入 `spring-ai-alibaba-graph-core`，避免在 demo 中硬编码不确定 Graph API。
- 后续可以保留 `WorkflowDefinition` 作为产品层 DSL，把 `WorkflowCompiler` 的输出从线性节点列表改为 Spring AI Alibaba `StateGraph` / `CompiledGraph`。
- `SimpleWorkflowRuntime` 是替换边界：未来可以新增 `GraphWorkflowRuntime`，仍由 `WorkflowService` 创建 run、统一 trace，并让 Graph 节点回调写 `run_step`。

可视化画布接入点：

- 前端画布只需要生成同样的 `nodes` / `edges` JSON。
- 后续可把节点 schema 固化为 registry：节点类型、配置表单、输入输出变量、校验规则。
- 当前 API 已可作为画布保存前的运行预览接口。

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

- 接入真实 `VectorStore` 和 `EmbeddingModel`，替换 `DocumentRetriever` 的 keyword 实现。
- 接入 Spring AI Alibaba Graph，把当前 service 编排升级成显式 workflow graph。
- 接入 MCP，把 `ToolService` 扩展为本地工具 + 远程 MCP 工具统一网关。
- 接入 Cairn Memory，为 coding-agent 或长会话场景补充行为记忆和连续性。
- 扩展成 Dify-like Workflow DSL：节点、边、变量、条件分支、工具节点、RAG 节点、trace 可视化。
