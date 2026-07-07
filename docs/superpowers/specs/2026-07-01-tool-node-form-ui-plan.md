# 工具节点表单化（Dify 式）改造计划书

**日期：** 2026-07-01
**目标：** 让非技术用户不写 JSON、不背工具名，就能在工作流画布中配置出「智能客服」这类含工具调用的工作流。
**范围：** 前端 Inspector 交互 + 后端工具元数据补齐。DSL 结构（`nodes` / `edges` / `config`）**零改动**。

---

## 1. 背景与问题定位

### 1.1 现状

当前画布节点面板提供的是纯技术原子节点（`start` / `llm` / `tool` / `condition` / `retriever` …）。要拼出智能客服模版（用户提问 → 意图识别 → 订单类调 `queryOrderAPI`、政策类走知识库检索 → 大模型生成回复），用户必须：

1. 在 `tool` 节点的 **裸文本框** 里手打内部工具名（如 `queryOrderAPI`）；
2. 在 `arguments` 的 **裸 JSON 编辑框** 里手写 `{"orderId": "{{input.orderId}}"}`；
3. 背下 `{{input}}`、`{{nodes.nodeId.field}}` 等变量模板语法。

### 1.2 代码层根因

| # | 问题 | 位置 |
|---|------|------|
| 1 | `toolName` 在 schema 中是 `string` 类型，前端渲染为 textarea | `WorkflowNodeSchemaRegistry.toolSchema()`（`src/main/java/com/example/agentdemo/workflow/WorkflowNodeSchemaRegistry.java` L123-156）；`controlForField()`（`src/main/resources/static/app.js` L3165-3185） |
| 2 | `arguments` 类型为 `object`，前端渲染为 `code-input` JSON textarea | 同上 |
| 3 | 本地工具 `ToolDescriptor.inputSchema` 全部为空串，前端即使想生成表单也无元数据可用（MCP 工具反而有 schema） | `LocalToolProvider.tools()`（`src/main/java/com/example/agentdemo/tool/LocalToolProvider.java` L46-55）；`ToolDescriptor`（同目录）|
| 4 | `expression` 字段带 `onlyForTool: calculate` 约束元数据，但前端无条件显示，对其他工具是噪音 | `WorkflowNodeSchemaRegistry.toolSchema()` + `renderInspector()`（app.js L1089 起）|
| 5 | 后端已返回 `templateVariables` 列表，但前端仅当说明文字，没有可点选的变量插入交互 | `WorkflowNodeSchemaRegistry` `TEMPLATE_VARIABLES`；app.js Inspector 渲染 |

### 1.3 已有可复用资产（不需要新建的东西）

- `GET /api/tools/catalog` → `ToolView(name, description, provider, remote, serverName, inputSchema, executable)`，已包含工具选择器所需全部数据（`ToolController` L40-43）。
- `POST /api/tools/{toolName}/test` → dry-run 执行 + 必填参数校验（`ToolTestService.validateRequiredArguments` 已经在消费 `inputSchema`），可直接支撑「节点内单步测试」。
- 前端已有按字段类型分发的控件工厂 `controlForField()`，参数表单可直接复用。
- `WorkflowVariableResolver` 已支持「字符串完全等于变量模板时保留原始类型」，表单生成的 `{{input.count}}` 数字参数不会被降级成字符串。

---

## 2. 总体设计

### 2.1 目标交互（对标 Dify）

```
Inspector（选中 tool 节点时）
┌─────────────────────────────────────┐
│ 节点名称  [订单查询           ]      │
│ 类型      工具                       │
│                                     │
│ 选择工具  [🔍 搜索工具…        ▼]   │  ← 下拉+搜索，来自 /api/tools/catalog
│   ├ 本地 · queryOrderAPI 订单查询    │     显示中文描述 + provider 徽标
│   ├ 本地 · calculate 算术计算        │     remote 未加白名单的置灰(不可选)
│   └ MCP  · xxx …                    │
│                                     │
│ ── 参数（按 inputSchema 生成）──     │
│ 订单号 *  [{{input.orderId}}  ] {x} │  ← 每个参数一个控件 + 变量插入按钮
│                                     │
│ ▸ 高级设置（折叠）                   │
│   幂等    [false ▼]                 │
│   重试次数 [0]  超时(ms) [0]         │
│                                     │
│ [▶ 测试该工具]                       │  ← 调 POST /api/tools/{name}/test
│   ↳ 结果/错误 内联展示               │
└─────────────────────────────────────┘
```

### 2.2 数据流

1. 画布加载时并行拉取 `GET /api/workflows/node-schemas` 与 `GET /api/tools/catalog`，工具目录缓存到 `state.toolCatalog`。
2. 选中 `tool` 节点 → Inspector 渲染工具选择器；选中某工具 → 解析该工具 `inputSchema`（JSON Schema）→ 按 `properties` 逐字段生成表单控件。
3. 表单值变更 → 组装回 `node.config.arguments`（object）与 `node.config.toolName`（string）。**存储格式与现有 DSL 完全一致**，后端编译器、runtime、AI 生成（`WorkflowGenerationService`）均不感知此改动。
4. 点「测试该工具」→ 用当前表单值（变量模板保留原文，或用示例值替换，见 4.4）调 `POST /api/tools/{toolName}/test`，结果内联展示。

### 2.3 分期

| 期 | 内容 | 依赖 |
|----|------|------|
| P1 | 后端：本地工具补 `inputSchema` 元数据 | 无 |
| P2 | 前端：工具选择器 + schema 驱动参数表单 + 条件显示 | P1 |
| P3 | 前端：节点内单步测试 | P2 |
| P4 | 前端：变量选择器（基于画布上游节点动态生成候选） | P2 |
| P5（可选后续） | 同思路推广到 `retriever` / `condition`；预置「智能客服」模版 | P2-P4 |

---

## 3. P1 — 后端：补齐本地工具 inputSchema

### 3.1 改动点

**文件：`src/main/java/com/example/agentdemo/tool/LocalToolProvider.java`**

`tools()` 返回的三个 `ToolDescriptor` 从 4 参构造改为 6 参构造，填入 JSON Schema（draft-07 子集），带中文 `title` / `description`：

- `getCurrentTime`：
  ```json
  {"type":"object","properties":{},"required":[]}
  ```
- `calculate`：
  ```json
  {"type":"object","properties":{"expression":{"type":"string","title":"算术表达式","description":"支持 + - * / 与括号，例如 (1+2)*3"}},"required":["expression"]}
  ```
- `queryOrderAPI`：
  ```json
  {"type":"object","properties":{"orderId":{"type":"string","title":"订单号","description":"用户提供的订单编号，例如 20260630001"}},"required":["orderId"]}
  ```
  注意：`LocalToolProvider.execute()` 里 `queryOrderAPI` 兼容 `user_query`/`query`/`orderId` 三个 key，schema 以 `orderId` 为标准入参，其余 key 保持运行时兼容、不进 schema。

Schema 字符串建议定义为类内 `private static final String` 常量（文本块），不引入新依赖、不做运行时构建。

### 3.2 约定（前后端契约）

- `inputSchema` 为 JSON Schema object 类型的字符串；空串表示「无 schema，前端回退到裸 JSON 编辑框」。
- 前端表单生成只消费以下子集：`properties`（含 `type` / `title` / `description` / `enum` / `default` / `minimum` / `maximum`）、`required`。超出子集的字段忽略。
- MCP 工具的 schema 由 `McpToolProvider` 透传，天然符合此契约。

### 3.3 测试

- `LocalToolProviderTest`（如无则新增）：断言三个工具的 `inputSchema` 可被 Jackson 解析、`required` 正确。
- 回归 `ToolTestServiceTest`：`queryOrderAPI` 缺 `orderId` 时 dry-run 返回校验失败（`validateRequiredArguments` 现在能真正生效于本地工具）。

### 3.4 验收标准

- `GET /api/tools/catalog` 返回的本地工具均带非空 `inputSchema`。
- 现有全部测试通过。

---

## 4. P2 — 前端：工具选择器 + 参数表单

改动集中在 `src/main/resources/static/app.js`（Inspector 渲染段，L1089-1200 附近与 L3165 控件工厂附近）与 `styles.css`。

### 4.1 工具目录加载

- 新增 `state.toolCatalog = []`；`loadSchemas()` 同批并行请求 `GET /api/tools/catalog`（失败时 toast 提示并回退旧行为，画布其余功能不受影响）。
- 提供 `findToolView(name)` 帮助函数。

### 4.2 工具选择器

`renderInspector()` 中对 `node.type === "tool"` 的 `toolName` 字段特判（模式与现有 `subgraph.definitionId` 特判一致，见 app.js L1148-1163）：

- 控件：`<select>`（工具 ≤ 15 个时）或「输入过滤 + 下拉列表」组合（工具多时，MCP 场景）。第一版建议直接 `<select>` + `<optgroup>` 按 provider 分组，实现成本最低。
- 选项文案：`中文/英文描述（name）`，如 `订单查询 API（queryOrderAPI）`；`remote && !executable` 的选项 `disabled` 并注明「未加入白名单」。
- 当前 `config.toolName` 不在目录中时（如目录加载失败、历史定义引用了已下线工具）：追加一个「(当前值) xxx」选项保留原值，不静默清空。
- 切换工具时：`config.toolName` 更新；`config.arguments` 中与新 schema 无交集的 key 清除，有交集的保留；触发参数区重渲染。

### 4.3 schema 驱动参数表单

- 新增 `renderToolArgumentsForm(node, toolView)`：
  - `JSON.parse(toolView.inputSchema)` 失败或为空 → 回退现有 JSON textarea（兼容路径，必须保留）。
  - 遍历 `properties`：每个参数一行，label 取 `title || name`，必填加 `*`，`description` 作为 hint 小字。
  - 控件复用/扩展 `controlForField()`：`string`→单行输入（长文本用 textarea）、`integer|number`→number 输入、`boolean`→下拉、`enum`→下拉。
  - 值写回：`node.config.arguments[paramName] = value`。数字/布尔参数若填的是变量模板字符串（如 `{{input.count}}`），按字符串原样保存——后端 `WorkflowVariableResolver` 会在运行时还原类型（README「当字符串完全等于一个变量模板时，解析器会保留原始类型」）。
- **条件显示**：`expression` 字段的 `constraints.onlyForTool === "calculate"` → 仅当 `config.toolName === "calculate"` 时渲染。实现为通用规则：schema 字段 `constraints.onlyForTool` 存在且不等于当前 toolName 时跳过。
- **高级设置折叠**：`idempotent` / `retryCount` / `timeoutMs` 收进 `<details>` 折叠区，降低首屏噪音。
- 保留一个「以 JSON 编辑」小开关（图标按钮），点击后切回原 JSON textarea——给高级用户逃生口，也覆盖 schema 表达不了的参数结构。

### 4.4 校验与提示

- 必填参数为空时：字段红框 + inline 提示；不阻塞保存（后端 `WorkflowCompiler` 仍是最终校验者），但「校验拓扑」按钮结果中可见。
- 前端不重复实现 JSON Schema 校验器，只做「必填非空」这一条，其余交给 P3 的 dry-run 测试暴露。

### 4.5 测试

- `FrontendStaticAssetsTest` 已存在：补充断言新 DOM 结构/脚本片段存在（该项目前端无独立测试框架，静态断言为现有约定）。
- 手工回归清单：
  1. 新建 tool 节点 → 默认选中 `getCurrentTime`、参数区为空表单；
  2. 切到 `queryOrderAPI` → 出现「订单号 *」输入框；
  3. 填 `{{input.orderId}}` → 保存 → 重新加载定义 → 表单回显正确；
  4. 加载一个 `arguments` 含 schema 外多余 key 的历史定义 → 自动回退 JSON 模式或多余 key 以 JSON 行展示（二选一，实现时定）；
  5. 校验拓扑 / 保存 / 运行全链路不回归。

### 4.6 验收标准

- 配置 `queryOrderAPI` 全程不需要手写 JSON、不需要记忆工具名。
- 旧定义（手写 arguments）打开不报错、可继续编辑。

---

## 5. P3 — 前端：节点内单步测试

### 5.1 改动点

- Inspector 底部（`删除节点` 按钮上方）为 `tool` 节点增加「测试该工具」按钮。
- 点击 → 收集当前参数表单值 → `POST /api/tools/{toolName}/test`。
- **变量模板处理**：参数值含 `{{…}}` 时弹出轻量「测试输入」浮层，让用户为每个变量填一个临时测试值（仅用于本次调用，不写回 config）。第一版可简化为：直接原样发送模板字符串，后端工具会把它当普通字符串处理，结果区提示「变量未替换，仅供连通性验证」。
- 结果内联展示：成功 → 绿色卡片显示 `output` JSON（格式化）；失败 → 红色卡片显示错误消息（`ToolExecutionLog.failure` 的 message）。
- 该接口本身会写 trace run（`ToolTestService` 已实现），无需额外埋点。

### 5.2 验收标准

- `queryOrderAPI` 填错订单号 → 按钮点击后能看到工具返回的「未找到订单」信息，用户无需去 Trace 页排查。

---

## 6. P4 — 前端：变量选择器

### 6.1 交互

- 每个参数输入框右侧加 `{x}` 图标按钮 → 弹出变量候选浮层，点击即在光标处插入模板。
- 候选分组：
  1. **输入**：`{{input}}`、`{{input.message}}`，以及从画布 start 后已配置内容里能推断的字段（第一版可只列 `{{input}}` + 手输 field 的输入框）；
  2. **上游节点**：遍历 `state.edges` 做反向可达分析，列出当前节点的所有上游节点，按节点类型给出输出字段候选——`llm` → `{{nodes.<id>.answer}}`、`tool` → `{{nodes.<id>.text}}`、`retriever` → `{{context}}` / `{{nodes.<id>.context}}`（各节点输出字段以 `WorkflowNodeSchemaRegistry` 每个 schema 的 `outputDescription` 与 `WorkflowNodeExecutor` 实际写入的 key 为准，实现前需核对一遍）；
  3. **快捷**：`{{lastOutput}}`、`{{toolResult}}`、`{{answer}}`。
- 显示文案用「节点 label + 字段中文名」，插入的是模板原文。

### 6.2 范围控制

- 只做「插入」，不做 Dify 那种强类型变量引用（值仍是模板字符串），因此后端零改动。
- 上游可达分析遇到环（loop）时按已访问集合截断即可。

### 6.3 验收标准

- 在 `queryOrderAPI` 的「订单号」参数上，用户可通过两次点击插入 `{{input.orderId}}`，全程不手打花括号。

---

## 7. P5（后续，不在本次实施范围）

1. **retriever 节点表单化**：知识库改为下拉选择（`GET /api/knowledge-bases` 已存在，app.js 已有 `kbDocs`/`kbSearch` API 常量）。
2. **condition 节点**：`operator` 改枚举下拉（equals / contains / greaterthan…，以 `WorkflowNodeExecutor` 实际支持列表为准）。
3. **智能客服预置模版**：表单化完成后，仿照现有 `insertLoopTemplate()`（app.js L1263-1299）新增 `insertCustomerServiceTemplate()`，一键插入：
   `start → llm(意图分类) → condition(订单类?) → [true] tool(queryOrderAPI) → llm(生成回复) → end`、`[false] retriever(知识库) → llm(生成回复) → end`，节点 label / route / 边 label 全部预填中文业务名。
4. **AI 生成协同**：`WorkflowGenerationService` 的 SYSTEM_PROMPT 中 tool 节点说明可加入可用工具清单（动态拼接 `listTools()`），减少 AI 编造工具名。

---

## 8. 风险与对策

| 风险 | 对策 |
|------|------|
| MCP 工具 inputSchema 复杂（嵌套 object / array / oneOf），表单渲染不了 | 表单器只支持契约子集（3.2）；超出即整体回退 JSON textarea，右上角提示「该工具参数较复杂，请用 JSON 编辑」 |
| 历史定义 arguments 与新 schema 不匹配 | 打开时做 key 对比：不匹配 → 回退 JSON 模式，绝不静默丢数据 |
| 工具目录接口失败导致 Inspector 不可用 | 目录加载失败时 toolName 回退为文本输入框（现状），其余功能不受影响 |
| `app.js` 单文件已 3000+ 行，继续膨胀 | 本次新增函数集中在「Inspector — tool 节点」注释段内；是否拆模块留给独立重构，不混入本次 |
| 前端无自动化测试框架 | 关键 DOM/函数存在性走 `FrontendStaticAssetsTest`；行为靠 4.5 手工回归清单 |

## 9. 明确不做（Out of Scope）

- DSL / `WorkflowCompiler` / runtime / 数据库结构改动。
- 节点市场、动态注册工具、租户权限。
- Dify 式强类型变量系统（变量仍是字符串模板）。
- `llm` 节点的模型下拉、`subgraph` 增强（已有基础特判，维持现状）。

## 10. 实施顺序与依赖总览

```
P1 后端元数据 ──▶ P2 选择器+表单 ──▶ P3 单步测试
                        └─────────▶ P4 变量选择器
P2~P4 全部完成后 ──▶ P5 客服模版预置（另立计划）
```

建议按 P1 → P2 → P3 → P4 顺序单独提交，每期独立可验收、可回滚。
