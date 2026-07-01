# 工业 MVP 复杂度热点优化设计

## 背景

当前 `agent-backend-demo` 已具备工业 MVP 所需的主流程能力，但多个模块已出现明显的职责堆积、重复逻辑和非线性扫描问题：

- `KnowledgeBaseService` 同时承担 KB CRUD、文档管理、导入、检索、分页预览和 DTO 映射。
- `AppRuntimeService` 同时承担快照解析、运行分发、知识检索上下文、SSE、trace、usage 等运行时职责。
- `WorkflowRunEventService` 的 SSE 路径仍采用全量 polling + 内存去重，处理复杂度随 polling 次数线性放大。
- `src/main/resources/static/app.js` 已成为前端大单文件，维护成本和回归风险持续升高。
- JSON 编解码、分页校验、公共 ID 生成在多个服务中重复实现。

本次优化目标不是新增功能，而是在不改变公开 API、不引入多租户、不引入 Node 构建链的前提下，降低时间复杂度、代码复杂度和重复逻辑。

## 目标

### 总体目标

- 降低后端热点服务的职责耦合和方法复杂度。
- 将全量扫描式路径收敛到带预算或增量 cursor 的可控复杂度。
- 统一公共横切工具，减少 `json/page/id` 三类重复实现。
- 拆分前端静态脚本，保持 no-build 交付方式。
- 维持现有 Controller API、静态资源访问路径和测试行为不变。

### 非目标

- 不实现真正的 KB-aware DashVector vector retrieval。
- 不引入真正的异步 workflow runtime push 模型。
- 不引入 React、Vite、npm build、TypeScript 或新的前端构建工具链。
- 不引入多租户、workspace、RBAC 等新领域能力。
- 不修改已有 Flyway migration，只允许新增 `V13`。

## 约束

- 公开 API 路径、请求/响应结构保持兼容。
- 现有测试必须继续通过，新增测试只用于锁定优化行为。
- 现有“parse failed 保存 FAILED document”的语义必须保留。
- 现有 workflow 事件语义仍然是 replay/poll，只优化为 delta/cursor。
- 前端仍通过 `index.html + script` 顺序加载，保留 `window.WorkflowCanvasController` 兼容层。

## 方案选择

本次采用 **方案 B，两阶段重构**：

- **阶段 1：结构性拆分与横切收敛**
  - `Knowledge*` 服务拆分
  - KB list grouped count 消除 N+1
  - 公共工具抽取
  - 前端 no-build 模块化
  - Tool registry / schema validation 复用
  - `V13` 数据库索引补强
- **阶段 2：运行时与事件复杂度优化**
  - `AppRuntimeService` 拆分为 facade + runners
  - `WorkflowRunEventService` 从全量 polling 改为 delta/cursor

这样可以优先完成低耦合、高收益优化，再在第二阶段处理运行时与事件流这一组高风险改造。

## 阶段 1 设计

### 1. Knowledge 模块拆分

#### 1.1 `KnowledgeBaseService`

保留为 KB 聚合根 facade，只负责：

- `create`
- `list`
- `get`

不得再承担：

- document ingestion
- document list/get/delete/reindex
- search/rerank
- chunk preview
- DTO mapping 细节

#### 1.2 `KnowledgeDocumentService`

负责：

- document list
- document get
- document delete
- document reindex

约束：

- Controller API 不变，只调整注入和内部委派。
- 复用统一分页校验工具。

#### 1.3 `KnowledgeIngestionService`

负责：

- text ingestion
- file ingestion
- MIME allowlist 判定
- 文件名安全清洗
- content hash 计算
- `DocumentEntity` 创建
- `DocumentIndexingService` 调用

必须保留：

- parse failed 仍保存 `FAILED` document
- MIME not allowed 仍直接拒绝，不保存 document

#### 1.4 `KnowledgeSearchService`

负责：

- keyword-first search
- rerank
- citation 构建

新增预算配置：

- `demo.knowledge.max-scanned-documents`
  - 第一优先配置
- 可选增强：`demo.knowledge.max-scanned-chunks`
  - 若 chunk-level 搜索成本可控，则优先采用 chunk-level keyword retrieval

阶段 1 允许两种实现：

- **保守实现**
  - 仍按 document 扫描，但引入 `maxScannedDocuments`
- **增强实现**
  - 改为 chunk-level keyword retrieval，并引入 `maxScannedChunks`

无论采用哪种实现，最终文档和结果汇报都必须明确：

- 当前仍是 `keyword-first retrieval`
- KB-aware vector retrieval 作为后续任务

#### 1.5 `KnowledgeChunkPreviewService`

负责 chunk preview，并提供统一分页：

- 默认 `page=0`
- 默认 `size=20`
- 不传时完全兼容旧 endpoint 语义

#### 1.6 `KnowledgeResponseMapper`

统一处理：

- `KnowledgeBaseEntity -> KnowledgeBaseResponse`
- `DocumentEntity -> KnowledgeDocumentResponse`
- chunk preview DTO 映射

目标：

- 消除 `KnowledgeBaseService` 中的表现层转换逻辑
- 为 grouped count 和 pagination 提供统一映射入口

### 2. KB list N+1 count 优化

新增 projection：

```java
public interface KbDocumentCountProjection {
    String getKbId();
    long getCount();
}
```

`DocumentRepository` 新增 grouped count 查询，一次性查出所有 `kbId -> count`，替代 list 过程中逐个 `countByOwnerIdAndKbId(...)`。

约束：

- list KB 时不得逐个发 count 查询
- 多 KB、多文档时 count 必须正确

### 3. 公共工具抽取

#### 3.1 `JsonPayloadCodec`

职责：

- 普通业务 JSON 序列化/反序列化
- 服务于 `AppService`、`Knowledge*` 服务，以及必要的 workflow 元数据读写

不迁移：

- `TraceService` 的 sanitizer / trace 专用 JSON 逻辑

#### 3.2 `PublicIdGenerator`

统一生成 public id，包括但不限于：

- app id
- kb id
- workflow definition id
- 其他对外暴露的短 ID

要求：

- 格式统一
- 单元测试可验证
- 服务类不再自行拼接前缀 + UUID 截断

#### 3.3 `PageRequestValidator`

统一处理：

- page/size 非负和上限校验
- `PageRequest` 构造

目标：

- `Knowledge*`
- `AppService`
- `WorkflowService`
- 其他已存在分页路径

逐步迁移到统一校验入口，避免重复逻辑和不一致的异常处理。

### 4. 前端 no-build 模块化

拆分目标：

- `src/main/resources/static/js/api.js`
- `src/main/resources/static/js/state.js`
- `src/main/resources/static/js/ui.js`
- `src/main/resources/static/js/workflow.js`
- `src/main/resources/static/js/apps.js`
- `src/main/resources/static/js/knowledge.js`
- `src/main/resources/static/js/runs.js`
- `src/main/resources/static/js/tools.js`
- `src/main/resources/static/js/settings.js`
- `src/main/resources/static/js/main.js`

兼容要求：

- 使用 `window.AgentWorkbench = window.AgentWorkbench || {}` 作为 namespace
- 继续暴露 `window.WorkflowCanvasController`
- `index.html` 顺序加载上述脚本
- `/`、`/index.html`、`/styles.css`、各 JS 模块静态访问保持可用

额外优化：

- workflow render 周期内建立 `nodesById` map，避免频繁 `find()`
- 拖拽阶段对 `renderEdges()` 使用 `requestAnimationFrame` debounce
- route summaries / highlight 对 `nodes/edges version` 做轻量 memoize

### 5. Tool registry / schema validation 复用

新增：

- `ToolRegistryCache`
  - TTL 30 秒
  - 提供 `find/list/views`
- `ToolSchemaValidator`
  - 第一阶段至少支持 `required`
  - 支持基础类型校验
  - 支持 `enum`

复用目标：

- `ToolTestService`
- `ToolGatewayService`

说明：

- 第一阶段不追求完整 JSON Schema 支持
- 现有 `oneOf/anyOf` 等复杂能力若已存在于某个 provider，可后续再向公共 validator 收敛

### 6. 数据库索引 `V13`

新增 migration：`V13__...sql`

建议索引：

```sql
create index if not exists idx_rag_documents_owner_kb_id
on rag_documents (owner_id, kb_id, id);

create index if not exists idx_rag_documents_owner_kb_status_id
on rag_documents (owner_id, kb_id, index_status, id);
```

要求：

- 不重复创建已有 `app_revisions`、`usage_records` 相关索引
- 编号连续且与现有 migration 兼容

## 阶段 2 设计

### 1. `AppRuntimeService` 拆分

最终结构：

- `AppRuntimeService`
  - facade
  - 按 `AppType` 分发
  - 目标控制在 `<= 120` 行
- `AppRuntimeSnapshotResolver`
  - 负责 published / draft / archived / snapshot loading
- `WorkflowAppRunner`
  - 负责 workflow app run
  - 包装 `RunContext`
  - 委托 `WorkflowService`
- `ChatAppRunner`
  - 负责同步 chat
  - 管理 memory、trace、usage、AI model
- `AgentAppRunner`
  - 负责 agent chat
- `AppStreamRunner`
  - 负责 SSE emitter、timeout、error、single-shot、chat stream
- `AppKnowledgeContextService`
  - 负责多 KB 检索、去重、排序、topK、prompt context 构造

其中 `AppKnowledgeContextService` 的去重策略为：

- 以 `documentId + chunkIndex` 为唯一键
- 同键保留最高 score

### 2. Workflow events delta/cursor

新增 `WorkflowRunEventCursor`：

- `lastStartedAt`
- `lastStepId`
- `sentTerminalStepIds`
- `runDoneSent`

Repository 层新增基于 cursor 的增量查询，建议以 `(startedAt, stepId)` 作为稳定排序与 cursor 边界，避免同毫秒遗漏。

`WorkflowRunEventService`：

- 保留 `snapshot(runId)` 用于 replay/test
- 新增 `delta(runId, cursor)`，仅返回新增 events

SSE endpoint：

- 改为循环使用 `delta(...)`
- 不再每 400 ms 全量读取所有 steps 并重建全部事件

兼容语义：

- `run_done` 只发送一次
- 仍然属于 `replay/poll` 优化版，不宣称“真正 async push”

### 3. 复杂度目标

阶段 2 完成后应满足：

- 多次 polling 不重复发送 step events
- `run_done` 只发送一次
- 100 step run 的事件处理接近 `O(stepCount)`，而不是 `O(pollCount * stepCount)`
- `AppRuntimeService` 不再直接混合 knowledge、workflow、chat、stream、snapshot 细节

## 测试策略

### 阶段 1

必须通过：

- `KnowledgeBaseIntegrationTest`
- `KnowledgeBaseSecurityTest`
- `ToolTestServiceIntegrationTest`
- `FrontendStaticAssetsTest`
- `./mvnw clean test`

新增测试：

- search 扫描预算生效
- chunk preview 分页生效
- 旧 API 兼容
- grouped count 正确且 list 不逐个 count
- `JsonPayloadCodec`
- `PublicIdGenerator`
- `PageRequestValidator`
- `ToolSchemaValidator` required/type/enum

### 阶段 2

必须通过：

- `AppLifecycleIntegrationTest`
- `AppSecurityTest`
- `AppApiKeyIntegrationTest`
- `*Workflow*`
- `./mvnw clean test`

新增测试：

- snapshot resolver published/draft/archived
- workflow runner 清理 `RunContext`
- chat runner 注入 KB context
- 多次 polling 不重复发送 step events
- `run_done` 只发送一次

### 最终验证

执行：

```bash
./mvnw -Dtest=*Knowledge* test
./mvnw -Dtest=*Workflow* test
./mvnw -Dtest=*App* test
./mvnw -Dtest=*Tool* test
./mvnw -Dtest=FrontendStaticAssetsTest test
./mvnw clean test
docker compose -f docker-compose.prod.yml config -q
docker compose -f docker-compose.prod.yml --profile proxy config -q
```

若 Docker daemon 可用，再执行：

```bash
docker build -t agent-backend-demo:complexity-refactor .
```

## 风险与回滚点

### 风险

- `KnowledgeBaseService` 拆分过程中，Controller 委托关系可能出现注入或事务边界回归。
- `AppRuntimeService` 拆分时最容易出现行为漂移，尤其是 trace、usage 和 SSE。
- workflow delta/cursor 若只追踪新 step，会漏掉已有 step 的终态更新，必须显式处理状态变化。
- 前端拆模块后，静态资源测试中的字符串断言和全局符号断言容易失效。

### 回滚点

- 阶段 1 与阶段 2 独立提交，避免大跨度不可回退变更。
- 阶段 2 开始前确保阶段 1 已完整绿灯。
- `snapshot()` 保留到阶段 2 完成后，作为 delta 行为对照和故障回退路径。

## 交付说明

最终汇报必须明确写出：

- Knowledge search 当前是 `keyword-first` 或 `chunk-level keyword retrieval`
- KB-aware vector retrieval 后续实现
- Workflow events 当前仍是 `replay/poll` 优化版，而不是真正 async push
- 各阶段实际执行的测试命令与结果
