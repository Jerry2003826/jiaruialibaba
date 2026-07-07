# 工业 MVP 复杂度热点优化实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 用两阶段重构降低 Knowledge、App Runtime、Workflow events、前端静态脚本和公共工具的复杂度，同时保持公开 API、无构建链前端和现有测试兼容。

**架构：** 阶段 1 先拆分低耦合、高收益的 `Knowledge*` 服务、公共工具、前端静态模块与 Tool 校验复用，并新增 `V13` 索引；阶段 2 再拆 `AppRuntimeService` 并将 workflow events 从全量 polling 改为 delta/cursor。每个阶段都先补失败测试，再做最小实现，最后运行阶段性回归与全量验证。

**技术栈：** Java 21、Spring Boot 3.5、Spring MVC、Spring Data JPA、Flyway、JUnit 5、MockMvc、原生浏览器静态脚本（无 Node build）。

---

## 文件结构

### 阶段 1：Knowledge / 公共工具 / 前端 / Tool / Flyway

**创建：**
- `src/main/java/com/example/agentdemo/knowledge/KnowledgeDocumentService.java`：文档 list/get/delete/reindex。
- `src/main/java/com/example/agentdemo/knowledge/KnowledgeIngestionService.java`：text/file ingestion、FAILED 语义、索引触发。
- `src/main/java/com/example/agentdemo/knowledge/KnowledgeSearchService.java`：search/rerank、扫描预算。
- `src/main/java/com/example/agentdemo/knowledge/KnowledgeChunkPreviewService.java`：chunk preview 分页。
- `src/main/java/com/example/agentdemo/knowledge/KnowledgeResponseMapper.java`：Knowledge DTO 映射。
- `src/main/java/com/example/agentdemo/knowledge/KbDocumentCountProjection.java`：KB grouped count projection。
- `src/main/java/com/example/agentdemo/common/JsonPayloadCodec.java`：普通业务 JSON 编解码。
- `src/main/java/com/example/agentdemo/common/PublicIdGenerator.java`：统一 public id 生成。
- `src/main/java/com/example/agentdemo/common/PageRequestValidator.java`：统一 page/size 校验。
- `src/main/java/com/example/agentdemo/tool/ToolRegistryCache.java`：TTL 30 秒工具缓存。
- `src/main/java/com/example/agentdemo/tool/ToolSchemaValidator.java`：required/type/enum 校验。
- `src/main/resources/db/migration/V13__rag_document_indexes.sql`：补充 `rag_documents` 索引。
- `src/main/resources/static/js/api.js`：接口与 endpoint 常量。
- `src/main/resources/static/js/state.js`：前端状态容器。
- `src/main/resources/static/js/ui.js`：通用 UI、toast、DOM 缓存。
- `src/main/resources/static/js/workflow.js`：工作流编辑、渲染与生成器逻辑。
- `src/main/resources/static/js/apps.js`：应用管理页逻辑。
- `src/main/resources/static/js/knowledge.js`：KB 页面逻辑。
- `src/main/resources/static/js/runs.js`：运行轨迹与事件回放逻辑。
- `src/main/resources/static/js/tools.js`：工具页逻辑。
- `src/main/resources/static/js/settings.js`：设置页逻辑。
- `src/main/resources/static/js/main.js`：入口与兼容层。

**修改：**
- `src/main/java/com/example/agentdemo/knowledge/KnowledgeBaseService.java`：收缩为 KB CRUD facade。
- `src/main/java/com/example/agentdemo/knowledge/KnowledgeProperties.java`：增加扫描预算配置。
- `src/main/java/com/example/agentdemo/rag/DocumentRepository.java`：新增 grouped count 查询。
- `src/main/java/com/example/agentdemo/knowledge/KnowledgeController.java`：保持 API 不变，委托拆分后服务。
- `src/main/java/com/example/agentdemo/app/AppService.java`：迁移到 `JsonPayloadCodec` / `PublicIdGenerator` / `PageRequestValidator`。
- `src/main/java/com/example/agentdemo/workflow/WorkflowDefinitionService.java`：迁移公共 id / JSON 工具。
- `src/main/java/com/example/agentdemo/workflow/WorkflowService.java`：迁移分页校验工具。
- `src/main/java/com/example/agentdemo/tool/ToolGatewayService.java`：复用 registry cache 与 schema validator。
- `src/main/java/com/example/agentdemo/tool/ToolTestService.java`：复用 schema validator。
- `src/main/resources/static/index.html`：改为顺序加载 `static/js/*.js`。
- `src/test/java/com/example/agentdemo/knowledge/KnowledgeBaseIntegrationTest.java`：预算、分页、兼容性、grouped count。
- `src/test/java/com/example/agentdemo/knowledge/KnowledgeBaseSecurityTest.java`：回归验证。
- `src/test/java/com/example/agentdemo/tool/ToolGatewayServiceTest.java`：schema validator 复用。
- `src/test/java/com/example/agentdemo/tool/ToolTestServiceIntegrationTest.java`：回归。
- `src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java`：多模块脚本、全局兼容层、核心 endpoint 字符串。

### 阶段 2：App Runtime / Workflow events

**创建：**
- `src/main/java/com/example/agentdemo/app/AppRuntimeSnapshotResolver.java`：published/draft/archived/snapshot 解析。
- `src/main/java/com/example/agentdemo/app/WorkflowAppRunner.java`：WORKFLOW app run。
- `src/main/java/com/example/agentdemo/app/ChatAppRunner.java`：CHAT app sync chat。
- `src/main/java/com/example/agentdemo/app/AgentAppRunner.java`：AGENT app chat。
- `src/main/java/com/example/agentdemo/app/AppStreamRunner.java`：SSE emitter、timeout、error、stream。
- `src/main/java/com/example/agentdemo/app/AppKnowledgeContextService.java`：多 KB 检索、去重、排序、prompt context。
- `src/main/java/com/example/agentdemo/workflow/WorkflowRunEventCursor.java`：事件增量 cursor。

**修改：**
- `src/main/java/com/example/agentdemo/app/AppRuntimeService.java`：收缩为 facade。
- `src/main/java/com/example/agentdemo/workflow/WorkflowRunEventService.java`：新增 `delta(runId, cursor)`。
- `src/main/java/com/example/agentdemo/workflow/WorkflowRunEventsSnapshot.java`：增加 cursor 元数据。
- `src/main/java/com/example/agentdemo/workflow/WorkflowController.java`：SSE 改走 delta/cursor。
- `src/main/java/com/example/agentdemo/trace/RunStepRepository.java`：增加 after cursor 查询。
- `src/main/java/com/example/agentdemo/trace/TraceService.java`：提供增量 step 查询入口。
- `src/test/java/com/example/agentdemo/app/AppSecurityTest.java`：回归。
- `src/test/java/com/example/agentdemo/app/AppLifecycleIntegrationTest.java`：回归。
- `src/test/java/com/example/agentdemo/app/apikey/AppApiKeyIntegrationTest.java`：回归。
- `src/test/java/com/example/agentdemo/workflow/WorkflowRunEventServiceTest.java`：新增 delta/cursor 行为测试。
- `src/test/java/com/example/agentdemo/app/AppRuntimeSnapshotResolverTest.java`：新增快照解析测试。
- `src/test/java/com/example/agentdemo/app/WorkflowAppRunnerTest.java`：新增 `RunContext` 清理测试。
- `src/test/java/com/example/agentdemo/app/ChatAppRunnerTest.java`：新增 KB context 注入测试。

## 任务 1：建立阶段 1 的 Knowledge 测试护栏

**文件：**
- 修改：`src/test/java/com/example/agentdemo/knowledge/KnowledgeBaseIntegrationTest.java`
- 修改：`src/test/java/com/example/agentdemo/knowledge/KnowledgeBaseSecurityTest.java`

- [ ] **步骤 1：编写失败的测试**

```java
@Test
void searchStopsAtConfiguredDocumentBudget() {
    // 预置多个文档，只允许扫描前 N 个，断言命中范围受预算限制。
}

@Test
void chunkPreviewUsesDefaultPageAndSize() {
    // 不传 page/size 时断言等价于 page=0,size=20。
}

@Test
void chunkPreviewRespectsExplicitPagination() {
    // page=1,size=2 时返回第二页内容。
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./mvnw -Dtest='*Knowledge*' test`
预期：FAIL，新增预算/分页断言因当前实现缺失而失败。

- [ ] **步骤 3：编写最少实现代码**

```java
// 这里只实现最小的 service 分拆骨架与扫描预算读取，不做 AppRuntime 或 workflow 事件改动。
```

- [ ] **步骤 4：运行测试验证通过**

运行：`./mvnw -Dtest='*Knowledge*' test`
预期：PASS，`KnowledgeBaseIntegrationTest` 与 `KnowledgeBaseSecurityTest` 全绿。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/example/agentdemo/knowledge \
        src/test/java/com/example/agentdemo/knowledge
git commit -m "refactor: split knowledge services"
```

## 任务 2：拆分 Knowledge 服务并消除 KB list N+1

**文件：**
- 创建：`src/main/java/com/example/agentdemo/knowledge/KnowledgeDocumentService.java`
- 创建：`src/main/java/com/example/agentdemo/knowledge/KnowledgeIngestionService.java`
- 创建：`src/main/java/com/example/agentdemo/knowledge/KnowledgeSearchService.java`
- 创建：`src/main/java/com/example/agentdemo/knowledge/KnowledgeChunkPreviewService.java`
- 创建：`src/main/java/com/example/agentdemo/knowledge/KnowledgeResponseMapper.java`
- 创建：`src/main/java/com/example/agentdemo/knowledge/KbDocumentCountProjection.java`
- 修改：`src/main/java/com/example/agentdemo/knowledge/KnowledgeBaseService.java`
- 修改：`src/main/java/com/example/agentdemo/rag/DocumentRepository.java`
- 修改：`src/main/java/com/example/agentdemo/knowledge/KnowledgeController.java`
- 修改：`src/main/java/com/example/agentdemo/knowledge/KnowledgeProperties.java`

- [ ] **步骤 1：编写 grouped count 失败测试**

```java
@Test
void listKnowledgeBasesLoadsDocumentCountsInBatch() {
    // 预置多个 KB 与文档，断言每个 count 正确，并通过 spy/repository 验证不走逐个 count。
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./mvnw -Dtest=KnowledgeBaseIntegrationTest test`
预期：FAIL，list 路径仍使用逐个 count。

- [ ] **步骤 3：实现 grouped count 与 service 拆分**

```java
@Query("""
select d.kbId as kbId, count(d.id) as count
from DocumentEntity d
where d.ownerId = :ownerId and d.kbId in :kbIds
group by d.kbId
""")
List<KbDocumentCountProjection> countByOwnerIdAndKbIdIn(String ownerId, Collection<String> kbIds);
```

- [ ] **步骤 4：运行知识库回归**

运行：`./mvnw -Dtest='*Knowledge*' test`
预期：PASS，Knowledge 相关测试通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/example/agentdemo/knowledge \
        src/main/java/com/example/agentdemo/rag/DocumentRepository.java \
        src/test/java/com/example/agentdemo/knowledge
git commit -m "perf: batch knowledge document counts"
```

## 任务 3：抽取公共 JSON / Page / ID 工具

**文件：**
- 创建：`src/main/java/com/example/agentdemo/common/JsonPayloadCodec.java`
- 创建：`src/main/java/com/example/agentdemo/common/PublicIdGenerator.java`
- 创建：`src/main/java/com/example/agentdemo/common/PageRequestValidator.java`
- 修改：`src/main/java/com/example/agentdemo/app/AppService.java`
- 修改：`src/main/java/com/example/agentdemo/knowledge/KnowledgeBaseService.java`
- 修改：`src/main/java/com/example/agentdemo/workflow/WorkflowDefinitionService.java`
- 修改：`src/main/java/com/example/agentdemo/workflow/WorkflowService.java`
- 测试：`src/test/java/com/example/agentdemo/common/JsonPayloadCodecTest.java`
- 测试：`src/test/java/com/example/agentdemo/common/PublicIdGeneratorTest.java`
- 测试：`src/test/java/com/example/agentdemo/common/PageRequestValidatorTest.java`

- [ ] **步骤 1：编写公共工具失败测试**

```java
@Test
void publicIdGeneratorUsesExpectedPrefixFormat() {
    assertThat(generator.next("kb")).startsWith("kb-");
}

@Test
void pageRequestValidatorRejectsNegativePage() {
    assertThatThrownBy(() -> validator.build(-1, 20)).isInstanceOf(BusinessException.class);
}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./mvnw -Dtest='*JsonPayloadCodec*','*PublicIdGenerator*','*PageRequestValidator*' test`
预期：FAIL，相关类尚不存在。

- [ ] **步骤 3：实现公共工具并迁移服务**

```java
public PageRequest build(int page, int size, int maxSize, String errorCode) {
    // 统一校验并返回 PageRequest
}
```

- [ ] **步骤 4：运行回归**

运行：`./mvnw -Dtest='*Knowledge*','*App*','*Workflow*' test`
预期：PASS，相关服务迁移后无回归。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/example/agentdemo/common \
        src/main/java/com/example/agentdemo/app/AppService.java \
        src/main/java/com/example/agentdemo/workflow \
        src/test/java/com/example/agentdemo/common
git commit -m "refactor: extract shared payload and paging utilities"
```

## 任务 4：前端 no-build 模块化与静态测试兼容

**文件：**
- 创建：`src/main/resources/static/js/api.js`
- 创建：`src/main/resources/static/js/state.js`
- 创建：`src/main/resources/static/js/ui.js`
- 创建：`src/main/resources/static/js/workflow.js`
- 创建：`src/main/resources/static/js/apps.js`
- 创建：`src/main/resources/static/js/knowledge.js`
- 创建：`src/main/resources/static/js/runs.js`
- 创建：`src/main/resources/static/js/tools.js`
- 创建：`src/main/resources/static/js/settings.js`
- 创建：`src/main/resources/static/js/main.js`
- 修改：`src/main/resources/static/index.html`
- 修改：`src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java`

- [ ] **步骤 1：先更新静态资源测试**

```java
.contains("/js/api.js")
.contains("/js/main.js")
.contains("window.WorkflowCanvasController")
.contains("/api/workflows")
.contains("/api/apps")
.contains("/api/knowledge-bases");
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./mvnw -Dtest=FrontendStaticAssetsTest test`
预期：FAIL，模块脚本尚未存在。

- [ ] **步骤 3：拆分脚本并保留兼容层**

```html
<script src="/js/api.js"></script>
<script src="/js/state.js"></script>
<script src="/js/ui.js"></script>
<script src="/js/workflow.js"></script>
<script src="/js/apps.js"></script>
<script src="/js/knowledge.js"></script>
<script src="/js/runs.js"></script>
<script src="/js/tools.js"></script>
<script src="/js/settings.js"></script>
<script src="/js/main.js"></script>
```

- [ ] **步骤 4：运行静态资源回归**

运行：`./mvnw -Dtest=FrontendStaticAssetsTest test`
预期：PASS，`/`、`/index.html`、各 JS 模块和 `WorkflowCanvasController` 均可访问。

- [ ] **步骤 5：Commit**

```bash
git add src/main/resources/static/index.html \
        src/main/resources/static/js \
        src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java
git commit -m "refactor: split workbench javascript modules"
```

## 任务 5：Tool registry cache 与 schema validator 复用

**文件：**
- 创建：`src/main/java/com/example/agentdemo/tool/ToolRegistryCache.java`
- 创建：`src/main/java/com/example/agentdemo/tool/ToolSchemaValidator.java`
- 修改：`src/main/java/com/example/agentdemo/tool/ToolGatewayService.java`
- 修改：`src/main/java/com/example/agentdemo/tool/ToolTestService.java`
- 修改：`src/test/java/com/example/agentdemo/tool/ToolGatewayServiceTest.java`
- 修改：`src/test/java/com/example/agentdemo/tool/ToolTestServiceIntegrationTest.java`

- [ ] **步骤 1：先补 schema validator 失败测试**

```java
@Test
void validatorRejectsMissingRequiredArgument() {}

@Test
void validatorRejectsWrongType() {}

@Test
void validatorRejectsValueOutsideEnum() {}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./mvnw -Dtest='*Tool*' test`
预期：FAIL，公共 validator / cache 尚未接入。

- [ ] **步骤 3：实现 cache 与 validator 并接入**

```java
public ValidationResult validate(JsonNode schema, Map<String, Object> arguments) {
    // required/type/enum
}
```

- [ ] **步骤 4：运行工具回归**

运行：`./mvnw -Dtest='*Tool*' test`
预期：PASS，Tool 相关测试通过。

- [ ] **步骤 5：Commit**

```bash
git add src/main/java/com/example/agentdemo/tool \
        src/test/java/com/example/agentdemo/tool
git commit -m "refactor: reuse tool registry cache and schema validation"
```

## 任务 6：新增 V13 索引并完成阶段 1 全量验证

**文件：**
- 创建：`src/main/resources/db/migration/V13__rag_document_indexes.sql`
- 测试：`src/test/java/com/example/agentdemo/migration/PostgresFlywayMigrationIntegrationTest.java`

- [ ] **步骤 1：新增 migration 文件**

```sql
create index if not exists idx_rag_documents_owner_kb_id
on rag_documents (owner_id, kb_id, id);

create index if not exists idx_rag_documents_owner_kb_status_id
on rag_documents (owner_id, kb_id, index_status, id);
```

- [ ] **步骤 2：运行 Flyway / 知识库相关测试**

运行：`./mvnw -Dtest='*Knowledge*','*Migration*' test`
预期：PASS；如 Docker 不可用，Postgres 集成测试按现有策略 skip。

- [ ] **步骤 3：运行阶段 1 验收**

运行：

```bash
./mvnw -Dtest='*Knowledge*' test
./mvnw -Dtest='*Tool*' test
./mvnw -Dtest=FrontendStaticAssetsTest test
./mvnw clean test
docker compose -f docker-compose.prod.yml config -q
docker compose -f docker-compose.prod.yml --profile proxy config -q
```

预期：全部通过。

- [ ] **步骤 4：Commit**

```bash
git add src/main/resources/db/migration/V13__rag_document_indexes.sql
git commit -m "perf: add rag document indexes for knowledge queries"
```

## 任务 7：先补阶段 2 的运行时与事件失败测试

**文件：**
- 创建：`src/test/java/com/example/agentdemo/app/AppRuntimeSnapshotResolverTest.java`
- 创建：`src/test/java/com/example/agentdemo/app/WorkflowAppRunnerTest.java`
- 创建：`src/test/java/com/example/agentdemo/app/ChatAppRunnerTest.java`
- 创建：`src/test/java/com/example/agentdemo/workflow/WorkflowRunEventServiceTest.java`
- 修改：`src/test/java/com/example/agentdemo/app/AppSecurityTest.java`
- 修改：`src/test/java/com/example/agentdemo/app/AppLifecycleIntegrationTest.java`
- 修改：`src/test/java/com/example/agentdemo/app/apikey/AppApiKeyIntegrationTest.java`

- [ ] **步骤 1：编写失败测试**

```java
@Test
void snapshotResolverLoadsPublishedRevision() {}

@Test
void workflowRunnerClearsRunContextAfterExecution() {}

@Test
void chatRunnerInjectsKnowledgeContext() {}

@Test
void deltaDoesNotRepeatPreviouslySentStepEvents() {}

@Test
void runDoneIsEmittedOnlyOnce() {}
```

- [ ] **步骤 2：运行测试验证失败**

运行：`./mvnw -Dtest='*Workflow*','*App*' test`
预期：FAIL，阶段 2 新增行为尚未实现。

- [ ] **步骤 3：Commit 测试脚手架**

```bash
git add src/test/java/com/example/agentdemo/app \
        src/test/java/com/example/agentdemo/workflow
git commit -m "test: lock runtime split and workflow delta behavior"
```

## 任务 8：拆分 AppRuntimeService

**文件：**
- 创建：`src/main/java/com/example/agentdemo/app/AppRuntimeSnapshotResolver.java`
- 创建：`src/main/java/com/example/agentdemo/app/WorkflowAppRunner.java`
- 创建：`src/main/java/com/example/agentdemo/app/ChatAppRunner.java`
- 创建：`src/main/java/com/example/agentdemo/app/AgentAppRunner.java`
- 创建：`src/main/java/com/example/agentdemo/app/AppStreamRunner.java`
- 创建：`src/main/java/com/example/agentdemo/app/AppKnowledgeContextService.java`
- 修改：`src/main/java/com/example/agentdemo/app/AppRuntimeService.java`

- [ ] **步骤 1：实现 snapshot resolver**

```java
public ResolvedAppRuntime resolve(String appId) {
    // published / draft / archived / snapshot
}
```

- [ ] **步骤 2：实现 runners 并缩减 facade**

运行：`./mvnw -Dtest='*App*' test`
预期：先 FAIL 再 PASS，`AppRuntimeService` 收缩且行为保持兼容。

- [ ] **步骤 3：运行 App 相关回归**

运行：`./mvnw -Dtest='*App*' test`
预期：PASS，`AppLifecycleIntegrationTest`、`AppSecurityTest`、`AppApiKeyIntegrationTest` 通过。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/example/agentdemo/app \
        src/test/java/com/example/agentdemo/app
git commit -m "refactor: split app runtime facade and runners"
```

## 任务 9：将 workflow events 改为 delta/cursor

**文件：**
- 创建：`src/main/java/com/example/agentdemo/workflow/WorkflowRunEventCursor.java`
- 修改：`src/main/java/com/example/agentdemo/workflow/WorkflowRunEventService.java`
- 修改：`src/main/java/com/example/agentdemo/workflow/WorkflowRunEventsSnapshot.java`
- 修改：`src/main/java/com/example/agentdemo/workflow/WorkflowController.java`
- 修改：`src/main/java/com/example/agentdemo/trace/RunStepRepository.java`
- 修改：`src/main/java/com/example/agentdemo/trace/TraceService.java`
- 修改：`src/test/java/com/example/agentdemo/workflow/WorkflowRunEventServiceTest.java`

- [ ] **步骤 1：实现 repository 增量查询**

```java
List<RunStepEntity> findByOwnerIdAndRunIdAndStartedAtAfterOrStartedAtEqualsAndStepIdGreaterThanOrderByStartedAtAscStepIdAsc(...);
```

- [ ] **步骤 2：实现 delta(runId, cursor)**

```java
public WorkflowRunEventsSnapshot delta(String runId, WorkflowRunEventCursor cursor) {
    // 仅返回新增 node_started / terminal / run_done
}
```

- [ ] **步骤 3：切换 SSE endpoint 到 delta**

运行：`./mvnw -Dtest='*Workflow*' test`
预期：PASS，不重复发送 step event，`run_done` 只发送一次。

- [ ] **步骤 4：Commit**

```bash
git add src/main/java/com/example/agentdemo/workflow \
        src/main/java/com/example/agentdemo/trace \
        src/test/java/com/example/agentdemo/workflow
git commit -m "perf: switch workflow events to delta cursor polling"
```

## 任务 10：阶段 2 与最终验收

**文件：**
- 修改：阶段 2 所有已变更文件
- 测试：全量回归

- [ ] **步骤 1：运行阶段 2 验收**

运行：

```bash
./mvnw -Dtest='*Workflow*' test
./mvnw -Dtest='*App*' test
```

预期：PASS。

- [ ] **步骤 2：运行最终验证**

运行：

```bash
./mvnw -Dtest='*Knowledge*' test
./mvnw -Dtest='*Workflow*' test
./mvnw -Dtest='*App*' test
./mvnw -Dtest='*Tool*' test
./mvnw -Dtest=FrontendStaticAssetsTest test
./mvnw clean test
docker compose -f docker-compose.prod.yml config -q
docker compose -f docker-compose.prod.yml --profile proxy config -q
```

若 Docker daemon 可用，再运行：

```bash
docker build -t agent-backend-demo:complexity-refactor .
```

预期：全部通过，Docker 不可用时明确记录未执行原因。

- [ ] **步骤 3：最终汇报**

```text
Summary：逐任务说明修改
Complexity improvements：Knowledge 预算 / workflow delta / AppRuntime 拆分 / 前端拆分
Tests：逐条命令与结果
Risk notes：keyword-first retrieval 与 replay/poll 语义说明
```
