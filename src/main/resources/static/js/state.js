"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};


  const TEMPLATE_VARIABLES = [
    "{{input}}", "{{input.message}}", "{{context}}", "{{lastOutput}}", "{{lastOutput.answer}}",
    "{{lastOutput.result}}", "{{lastOutput.outputs}}", "{{state.field}}", "{{nodes.nodeId.field}}",
    "{{toolResult}}", "{{answer}}"
  ];

  const templateConstraints = { templateVariables: TEMPLATE_VARIABLES };

  const fallbackSchemas = [
    { type: "start", displayName: "Start", configFields: [] },
    { type: "retriever", displayName: "Retriever", configFields: [
      { name: "query", type: "string", defaultValue: "{{input.message}}", constraints: templateConstraints },
      { name: "topK", type: "integer", defaultValue: 3 }
    ] },
    { type: "tavily_search", displayName: "Tavily Search", group: "Knowledge", configFields: [
      { name: "query", type: "string", required: true, defaultValue: "{{input.message}}", constraints: templateConstraints },
      { name: "searchDepth", type: "string", defaultValue: "basic", constraints: { allowedValues: ["basic", "advanced", "fast", "ultra-fast"] } },
      { name: "topic", type: "string", defaultValue: "general", constraints: { allowedValues: ["general", "news", "finance"] } },
      { name: "maxResults", type: "integer", defaultValue: 5, constraints: { min: 1, max: 20 } },
      { name: "includeAnswer", type: "boolean", defaultValue: false },
      { name: "includeRawContent", type: "boolean", defaultValue: false },
      { name: "timeRange", type: "string", defaultValue: null, constraints: { allowedValues: ["day", "week", "month", "year"] } },
      { name: "includeDomains", type: "array", defaultValue: [] },
      { name: "excludeDomains", type: "array", defaultValue: [] }
    ] },
    { type: "llm", displayName: "LLM", configFields: [
      { name: "prompt", type: "string", defaultValue: "Answer using this context: {{context}}\nInput: {{input}}", constraints: templateConstraints },
      { name: "model", type: "string", defaultValue: "" },
      { name: "outputMode", type: "string", defaultValue: "text", constraints: { allowedValues: ["text", "json"] } },
      { name: "outputSchema", type: "object", defaultValue: {} }
    ] },
    { type: "tool", displayName: "Tool", configFields: [
      { name: "toolName", type: "string", defaultValue: "getCurrentTime" },
      { name: "arguments", type: "object", defaultValue: {}, constraints: templateConstraints },
      { name: "expression", type: "string", defaultValue: "" },
      { name: "idempotent", type: "boolean", defaultValue: false }
    ] },
    { type: "http_request", displayName: "HTTP Request", group: "Tools", configFields: [
      { name: "method", type: "string", defaultValue: "GET", constraints: { allowedValues: ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"] } },
      { name: "url", type: "string", required: true, defaultValue: "", constraints: templateConstraints },
      { name: "headers", type: "array", defaultValue: [], constraints: templateConstraints },
      { name: "params", type: "array", defaultValue: [], constraints: templateConstraints },
      { name: "authorization", type: "object", defaultValue: { type: "none" } },
      { name: "body", type: "object", defaultValue: { type: "none", value: "" }, constraints: templateConstraints },
      { name: "idempotent", type: "boolean", defaultValue: false },
      { name: "continueOnError", type: "boolean", defaultValue: false },
      { name: "retryCount", type: "integer", defaultValue: 0 },
      { name: "timeoutMs", type: "integer", defaultValue: 30000 }
    ] },
    { type: "report_export", displayName: "Report Export", group: "Tools", configFields: [
      { name: "content", type: "string", required: true, defaultValue: "", constraints: templateConstraints },
      { name: "formats", type: "array", required: true, defaultValue: ["pdf"] },
      { name: "fileName", type: "string", defaultValue: "研究报告", constraints: templateConstraints },
      { name: "title", type: "string", defaultValue: "研究报告", constraints: templateConstraints },
      { name: "author", type: "string", defaultValue: "", constraints: templateConstraints },
      { name: "organization", type: "string", defaultValue: "", constraints: templateConstraints },
      { name: "theme", type: "string", defaultValue: "business", constraints: { allowedValues: ["business", "minimal", "academic"] } },
      { name: "paperSize", type: "string", defaultValue: "A4", constraints: { allowedValues: ["A4", "Letter"] } },
      { name: "orientation", type: "string", defaultValue: "portrait", constraints: { allowedValues: ["portrait", "landscape"] } },
      { name: "includeToc", type: "boolean", defaultValue: true },
      { name: "includePageNumbers", type: "boolean", defaultValue: true },
      { name: "retentionDays", type: "integer", defaultValue: 30, constraints: { min: 1, max: 365 } }
    ] },
    { type: "custom", displayName: "Custom", group: "Tools", configFields: [
      { name: "mode", type: "string", defaultValue: "ai", constraints: { allowedValues: ["ai", "template"] } },
      { name: "inputs", type: "object", defaultValue: {}, constraints: templateConstraints },
      { name: "instruction", type: "string", defaultValue: "" },
      { name: "template", type: "any", defaultValue: "", constraints: templateConstraints },
      { name: "model", type: "string", defaultValue: "" },
      { name: "outputMode", type: "string", defaultValue: "text", constraints: { allowedValues: ["text", "json"] } },
      { name: "outputSchema", type: "object", defaultValue: {} }
    ] },
    { type: "condition", displayName: "Condition", configFields: [
      { name: "left", type: "string", defaultValue: "{{input}}", constraints: templateConstraints },
      { name: "operator", type: "string", defaultValue: "contains", constraints: { allowedValues: ["equals", "notEquals", "contains", "notContains", "startsWith", "endsWith", "exists", "notExists", "greaterThan", "lessThan"] } },
      { name: "right", type: "any", defaultValue: "", constraints: templateConstraints },
      { name: "mode", type: "string", defaultValue: "all", constraints: { allowedValues: ["all", "any"] } },
      { name: "conditions", type: "array", defaultValue: [], constraints: templateConstraints },
      { name: "caseSensitive", type: "boolean", defaultValue: false }
    ] },
    { type: "parallel", displayName: "Parallel", configFields: [] },
    { type: "join", displayName: "Join", configFields: [] },
    { type: "variable_aggregator", displayName: "Variable Aggregator", group: "Flow Control", configFields: [
      { name: "mode", type: "string", defaultValue: "single", constraints: { allowedValues: ["single", "groups"] } },
      { name: "outputType", type: "string", defaultValue: "string", constraints: { allowedValues: ["string", "number", "boolean", "object", "array"] } },
      { name: "variables", type: "array", defaultValue: [] },
      { name: "groups", type: "array", defaultValue: [] }
    ] },
    { type: "loop", displayName: "Loop", configFields: [
      { name: "maxIterations", type: "integer", defaultValue: 10 },
      { name: "left", type: "string", defaultValue: "{{input.count}}", constraints: templateConstraints },
      { name: "operator", type: "string", defaultValue: "greaterThan", constraints: { allowedValues: ["equals", "notEquals", "contains", "notContains", "startsWith", "endsWith", "exists", "notExists", "greaterThan", "lessThan"] } },
      { name: "right", type: "string", defaultValue: "0", constraints: templateConstraints }
    ] },
    { type: "loop_back", displayName: "Loop Back", configFields: [] },
    { type: "subgraph", displayName: "Subgraph", configFields: [{ name: "definitionId", type: "string", defaultValue: "" }, { name: "version", type: "integer", defaultValue: null }] },
    { type: "dynamic", displayName: "Dynamic", configFields: [
      { name: "itemsFrom", type: "string", defaultValue: "{{input.tools}}", constraints: templateConstraints },
      { name: "action", type: "string", defaultValue: "tool" },
      { name: "allowedTools", type: "array", defaultValue: [] }
    ] },
    { type: "end", displayName: "End", configFields: [] }
  ];

  const NODE_COLORS = {
    start: "var(--node-start)", end: "var(--node-end)",
    retriever: "var(--node-rag)", tavily_search: "var(--node-rag)", llm: "var(--node-llm)", tool: "var(--node-tool)",
    http_request: "#7c3aed", report_export: "#0f766e", custom: "#db2777", condition: "var(--node-condition)", parallel: "var(--node-parallel)", join: "var(--node-join)",
    variable_aggregator: "#2563eb",
    loop: "var(--node-loop)", loop_back: "var(--node-loopback)", subgraph: "var(--node-flow)", dynamic: "var(--node-flow)"
  };

  const NODE_LABELS = {
    start: "开始", retriever: "检索", tavily_search: "Tavily 搜索", llm: "大模型", tool: "工具",
    http_request: "HTTP 请求", report_export: "报告导出", custom: "自由节点", condition: "条件", parallel: "并行", join: "汇合", variable_aggregator: "变量聚合器", loop: "循环",
    loop_back: "循环回边", subgraph: "子图", dynamic: "动态", end: "结束"
  };

  const ROUTE_RULES = [
    { id: "return", label: "退货流程", short: "退货", keywords: ["退货", "退回", "拒收", "return", "return_flow", "return_policy"] },
    { id: "refund", label: "退款流程", short: "退款", keywords: ["退款", "到账", "refund", "refund_flow", "after_sales"] },
    { id: "logistics", label: "物流查询", short: "物流", keywords: ["物流", "快递", "运单", "发货", "包裹", "配送", "tracking", "shipment", "delivery"] },
    { id: "order", label: "订单查询", short: "订单", keywords: ["订单", "订单号", "queryOrderAPI", "order_query", "order id", "order number"] },
    { id: "knowledge", label: "知识库问答", short: "知识库", keywords: ["知识库", "检索", "文档", "上下文", "流程", "政策", "规则", "rag", "retriever", "context", "policy", "guide"], typeHints: ["retriever"] },
    { id: "fallback", label: "兜底处理", short: "兜底", keywords: ["兜底", "异常", "失败", "澄清", "人工", "fallback", "error", "clarification", "default"] }
  ];

  // 简洁线性图标（inner SVG，viewBox 0 0 24 24）
  const NODE_ICONS = {
    start: '<circle cx="12" cy="12" r="8"/><path d="M10 9l5 3-5 3z"/>',
    end: '<circle cx="12" cy="12" r="8"/><rect x="9.5" y="9.5" width="5" height="5" rx="1"/>',
    retriever: '<circle cx="11" cy="11" r="6"/><path d="M16 16l4 4"/>',
    tavily_search: '<circle cx="12" cy="12" r="8"/><path d="M4 12h16M12 4c3 3 3 13 0 16M12 4c-3 3-3 13 0 16"/>',
    llm: '<path d="M12 3l1.8 4.2L18 9l-4.2 1.8L12 15l-1.8-4.2L6 9l4.2-1.8z"/><path d="M18 15l.9 2.1L21 18l-2.1.9L18 21l-.9-2.1L15 18l2.1-.9z"/>',
    tool: '<path d="M14 6a3 3 0 0 1 4 4l-8 8-4 1 1-4z"/>',
    http_request: '<path d="M4 7h16v10H4z"/><path d="M8 11h8M13 8l3 3-3 3"/>',
    report_export: '<path d="M6 3h9l3 3v15H6z"/><path d="M15 3v4h4M9 12h6M9 16h6"/><path d="M12 8v5m0 0l-2-2m2 2l2-2"/>',
    custom: '<path d="M4 6h16M4 12h16M4 18h16"/><circle cx="8" cy="6" r="2"/><circle cx="16" cy="12" r="2"/><circle cx="10" cy="18" r="2"/>',
    condition: '<circle cx="6" cy="6" r="2.4"/><circle cx="18" cy="6" r="2.4"/><circle cx="12" cy="18" r="2.4"/><path d="M6 8v3a3 3 0 0 0 3 3h6a3 3 0 0 0 3-3V8"/><path d="M12 14v2"/>',
    parallel: '<path d="M12 4v6M12 10c0 3-5 2-5 6M12 10c0 3 5 2 5 6"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="18" r="2"/>',
    join: '<path d="M7 4c0 4 5 3 5 6s-5 2-5 6M17 4c0 4-5 3-5 6"/><circle cx="12" cy="18" r="2"/>',
    variable_aggregator: '<path d="M5 5v4c0 2 2 3 4 3h6M5 19v-4c0-2 2-3 4-3"/><path d="M15 8l4 4-4 4"/>',
    loop: '<path d="M4 12a8 8 0 0 1 14-5M20 12a8 8 0 0 1-14 5"/><path d="M18 4v4h-4M6 20v-4h4"/>',
    loop_back: '<path d="M9 7L4 12l5 5"/><path d="M4 12h11a5 5 0 0 1 0 10h-1"/>',
    subgraph: '<rect x="4" y="4" width="7" height="7" rx="1.5"/><rect x="13" y="13" width="7" height="7" rx="1.5"/><path d="M11 8h4v5"/>',
    dynamic: '<path d="M13 3L5 13h6l-1 8 8-10h-6z"/>',
    end_default: '<circle cx="12" cy="12" r="8"/>'
  };

  const CANVAS_POSITIONS_KEY_PREFIX = "workflow-canvas-positions:";
  const ASSISTANT_WORKFLOW_BINDING_KEY = "assistant-workflow-binding";
  const ZOOM_MIN = 0.35;
  const ZOOM_MAX = 2;
  const DEFAULT_RUN_INPUT = '{"message":"这个智能体能从文档里回答什么？"}';

  const state = {
    schemas: fallbackSchemas,
    health: null,
    nodes: [],
    edges: [],
    positions: new Map(),
    selectedNodeId: null,
    connectSourceId: null,
    connectSourceBranch: "",
    definitionId: null,
    definitionVersion: null,
    definitionStatus: null,
    savedDefinitions: [],
    toolCatalog: [],
    toolCatalogLoaded: false,
    lastRunId: null,
    toastTimer: null,
    undoStack: [],
    view: { scale: 1, panX: 40, panY: 30 },
    inputMode: "form",
    chatMode: "chat",
    chatHistories: { chat: [], assistant: [] },
    activeRunCardId: null,
    editingDocumentId: null,
    editingOrderId: null,
    assistantWorkflowBound: false,
    assistantWorkflowUseCanvas: false,
    assistantWorkflowDefinitionId: null,
    assistantWorkflowDefinitionVersion: null,
    assistantWorkflowDefinitionName: null,
    assistantWorkflowDefinitionStatus: null,
    routeFilters: new Set(),
    routePanelCollapsed: true,
    httpCredentials: [],
    httpCredentialsLoaded: false,
    workflowVariables: { inputs: [], outputs: [] }
  };

  const els = {};
  let authToken = null; // 每次 API/SSE 调用的 Bearer，由 bootstrapAuth() 在本地/demo 模式填充

  const viewRoutes = {
    apps: () => void loadApps(),
    workflow: () => {},
    chat: () => renderChat(),
    kb: () => void loadKnowledgeBases(),
    library: () => void loadLibraryData(),
    runs: () => void loadRuns(),
    tools: () => void loadTools(),
    settings: () => void loadSettings()
  };

  function escAppHtml(value) {
    return String(value ?? "").replace(/[&<>"']/g, (ch) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[ch]));
  }

  const FIELD_LABELS = {
    topK: "检索条数", query: "搜索内容", searchDepth: "搜索深度", topic: "搜索类别",
    maxResults: "结果数量", includeAnswer: "生成摘要", includeRawContent: "包含网页原文",
    timeRange: "时间范围", includeDomains: "限定网站", excludeDomains: "排除网站",
    prompt: "提示词模板", model: "模型名称", outputMode: "输出模式",
    outputSchema: "输出结构约束", toolName: "调用工具",
    left: "判断左值", operator: "判断条件", right: "判断右值", maxIterations: "最大循环次数",
    mode: "复合条件模式", conditions: "复合条件列表", caseSensitive: "区分大小写",
    itemsFrom: "动态数据来源", action: "执行动作", definitionId: "子工作流", version: "子工作流版本",
    allowedTools: "允许工具", arguments: "工具参数", expression: "计算表达式",
    writeState: "写入状态", timeoutMs: "超时时间", retryCount: "重试次数", idempotent: "可安全重试",
    method: "请求方法", url: "请求地址", headers: "Headers", params: "Params", authorization: "鉴权", body: "请求体",
    outputType: "输出类型", variables: "候选变量", groups: "聚合分组", continueOnError: "失败后继续",
    content: "报告内容", formats: "导出格式", fileName: "文件名", title: "报告标题",
    author: "作者", organization: "机构", theme: "主题", paperSize: "纸张",
    orientation: "方向", includeToc: "包含目录", includePageNumbers: "包含页码", retentionDays: "保留天数",
    inputs: "命名输入", instruction: "业务要求", template: "输出模板"
  };

  const OPTION_LABELS = {
    outputMode: { text: "文本", json: "JSON" },
    searchDepth: { basic: "基础", advanced: "深度", fast: "快速", "ultra-fast": "极速" },
    topic: { general: "通用", news: "新闻", finance: "财经" },
    timeRange: { day: "最近一天", week: "最近一周", month: "最近一月", year: "最近一年" },
    operator: {
      equals: "等于", notEquals: "不等于", contains: "包含", notContains: "不包含",
      startsWith: "开头是", endsWith: "结尾是", exists: "存在", notExists: "不存在",
      greaterThan: "大于", lessThan: "小于", greaterthan: "大于", lessthan: "小于",
      notequals: "不等于", notcontains: "不包含", startswith: "开头是", endswith: "结尾是",
      notexists: "不存在"
    },
    mode: { all: "全部满足", any: "任一满足", single: "单一输出", groups: "分组输出" },
    outputType: { string: "文本", number: "数字", boolean: "布尔", object: "对象", array: "列表" },
    theme: { business: "商务", minimal: "极简", academic: "学术" },
    paperSize: { A4: "A4", Letter: "Letter" },
    orientation: { portrait: "纵向", landscape: "横向" }
  };

  const VARIABLE_LABELS = {
    message: "用户消息", input: "输入内容", context: "检索上下文", output: "输出结果", answer: "回答内容",
    parsed: "结构化结果", result: "判断结果", results: "结果列表", query: "检索问题", topic: "搜索主题",
    retrievedContext: "引用知识", outputs: "输出列表", itemCount: "列表数量", prompt: "提示词",
    model: "模型名称", tokenUsage: "Token 用量", fallback: "是否降级", errorMessage: "错误信息",
    toolCalls: "工具调用", text: "文本内容", found: "是否找到", runId: "运行编号",
    conversationId: "会话编号", history: "上下文历史", user_query: "用户查询内容",
    intent: "意图", orderIds: "订单号列表", hasOrderId: "已有订单号",
    needsOrderId: "需要订单号", confidence: "置信度", tools: "工具列表", count: "数量",
    amount: "金额", priority: "优先级", expenseType: "报销类型",
    statusCode: "状态码", headers: "响应头", body: "响应正文", json: "JSON 响应", durationMs: "耗时", succeeded: "请求成功",
    exportId: "导出编号", artifacts: "报告文件", primary: "主报告", printPreview: "打印预览", expiresAt: "过期时间",
    receiptProvided: "已提供票据", orderLookupReady: "订单查询就绪",
    orderLookupCount: "订单查询次数", orderToolCalls: "订单工具调用",
    currentOrderIds: "当前订单号", recentOrderIds: "最近订单号",
    referencedOrderIds: "已引用订单号"
  };

  const VARIABLE_PRESETS = [
    { value: "{{input.message}}", label: "输入内容 · 用户消息" },
    { value: "{{input.tools}}", label: "输入内容 · 工具列表" },
    { value: "{{input.count}}", label: "输入内容 · 数量" },
    { value: "{{input}}", label: "输入内容 · 完整对象" },
    { value: "{{context}}", label: "检索上下文" },
    { value: "{{lastOutput.answer}}", label: "上一步输出 · 回答内容" },
    { value: "{{lastOutput.result}}", label: "上一步输出 · 判断结果" },
    { value: "{{lastOutput.outputs}}", label: "上一步输出 · 输出列表" },
    { value: "{{lastOutput.retrievedContext}}", label: "上一步输出 · 引用知识" },
    { value: "{{answer}}", label: "最近模型回答" },
    { value: "{{toolResult}}", label: "最近工具结果" }
  ];

  const DEFAULT_NODE_OUTPUT_FIELDS = {
    start: ["__self__", "message"],
    retriever: ["__self__", "query", "retrievedContext"],
    tavily_search: ["__self__", "query", "answer", "results", "responseTime", "requestId"],
    llm: ["__self__", "answer", "parsed"],
    tool: ["__self__"],
    http_request: ["__self__", "statusCode", "headers", "body", "json", "durationMs", "succeeded"],
    report_export: ["__self__", "exportId", "artifacts", "primary", "printPreview", "expiresAt"],
    custom: ["__self__", "mode", "inputs", "output", "answer", "parsed"],
    condition: ["__self__", "result"],
    loop: ["__self__", "result"],
    parallel: ["__self__", "outputs"],
    join: ["__self__", "outputs"],
    variable_aggregator: ["__self__", "output"],
    dynamic: ["__self__", "outputs", "itemCount"],
    subgraph: ["__self__", "output"],
    end: ["__self__", "output"]
  };

  function configFieldLabel(name) { return FIELD_LABELS[name] || variableLabel(name); }
  function variableLabel(name) { return VARIABLE_LABELS[name] || FIELD_LABELS[name] || `自定义变量（${name}）`; }
  function optionLabel(fieldName, value) { return OPTION_LABELS[fieldName]?.[String(value)] || String(value); }
  function supportsTemplateVariables(field) { return Array.isArray(field.constraints?.templateVariables); }

  AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || [];
  AgentWorkbench.loadedModules.push("state");
  AgentWorkbench.state = state;
  AgentWorkbench.constants = {
    fallbackSchemas,
    NODE_COLORS,
    NODE_LABELS,
    ROUTE_RULES,
    NODE_ICONS,
    CANVAS_POSITIONS_KEY_PREFIX,
    ASSISTANT_WORKFLOW_BINDING_KEY,
    ZOOM_MIN,
    ZOOM_MAX,
    DEFAULT_RUN_INPUT,
    FIELD_LABELS,
    OPTION_LABELS,
    VARIABLE_LABELS,
    VARIABLE_PRESETS,
    DEFAULT_NODE_OUTPUT_FIELDS
  };
  AgentWorkbench.variableLabel = variableLabel;
  AgentWorkbench.configFieldLabel = configFieldLabel;
  AgentWorkbench.conditionRuleControl = conditionRuleControl;
  window.AgentWorkbench.state = state;
  window.AgentWorkbench.constants = AgentWorkbench.constants;
  window.AgentWorkbench.variableLabel = variableLabel;
  window.AgentWorkbench.configFieldLabel = configFieldLabel;
  window.AgentWorkbench.conditionRuleControl = conditionRuleControl;

  function nodeSummary(node) {
    if (node.type === "condition") return cleanText(node.route);
    if (cleanText(node.route)) return cleanText(node.route);
    if (node.type === "llm") return String(node.config.prompt || "提示词").slice(0, 54);
    if (node.type === "tool") return String(node.config.toolName || "getCurrentTime");
    if (node.type === "http_request") return `${node.config.method || "GET"} ${node.config.url || "未配置 URL"}`.slice(0, 54);
    if (node.type === "report_export") {
      const formats = Array.isArray(node.config.formats) ? node.config.formats : ["pdf"];
      return `${node.config.fileName || "研究报告"} · ${formats.map((item) => String(item).toUpperCase()).join(" / ")}`.slice(0, 54);
    }
    if (node.type === "custom") {
      if ((node.config.mode || "ai") === "template") {
        return `模板转换 · ${Object.keys(node.config.inputs || {}).length} 个输入`;
      }
      return String(node.config.instruction || "等待填写业务要求").slice(0, 54);
    }
    if (node.type === "variable_aggregator") {
      const count = node.config.mode === "groups" ? (node.config.groups || []).length : (node.config.variables || []).length;
      return `${node.config.mode === "groups" ? "分组" : "单一"}聚合 · ${count}`;
    }
    if (node.type === "retriever") return `检索条数 ${node.config.topK || 3}`;
    if (node.type === "subgraph") return node.config.definitionId ? `定义 ${node.config.definitionId}` : "子图";
    if (node.type === "loop") return `最多循环 ${node.config.maxIterations || 10} 次`;
    if (node.type === "dynamic") return String(node.config.itemsFrom || "items").slice(0, 40);
    if (node.type === "start" || node.type === "end") return "";
    return "";
  }

  // 变量模板的短标签：用于节点卡片上的分支描述
  function templateShortLabel(value) {
    const text = String(value ?? "").trim();
    if (!text) return "";
    const preset = VARIABLE_PRESETS.find((item) => item.value === text);
    if (preset) return preset.label.split(" · ").pop();
    const nodeRef = text.match(/^\{\{nodes\.([^.}]+)\.?(.*?)\}\}$/);
    if (nodeRef) {
      const refNode = state.nodes.find((n) => n.id === nodeRef[1]);
      const base = refNode ? nodeDisplayName(refNode) : nodeRef[1];
      if (!nodeRef[2]) return base;
      const schemaTitle = schemaTitleForPath(refNode, nodeRef[2]);
      const tail = nodeRef[2].split(".").pop();
      return `${base}·${schemaTitle || schemaFieldLabel(tail)}`;
    }
    return text.replace(/^\{\{/, "").replace(/\}\}$/, "");
  }

  // 沿节点 outputSchema 找 parsed.x.y 对应字段的显示名（title）
  function schemaTitleForPath(node, path) {
    if (!node || !path.startsWith("parsed.")) return "";
    const segments = path.slice("parsed.".length).split(".").filter(Boolean);
    if (segments.length === 0) return "";
    let schema = node.config?.outputSchema;
    let title = "";
    for (const segment of segments) {
      const properties = schema && typeof schema === "object" && !Array.isArray(schema) ? schema.properties : null;
      const prop = properties && typeof properties === "object" && !Array.isArray(properties) ? properties[segment] : null;
      if (!prop || typeof prop !== "object" || Array.isArray(prop)) return "";
      title = typeof prop.title === "string" && prop.title.trim() ? prop.title.trim() : "";
      schema = prop;
    }
    return title;
  }

  // 条件节点 IF 分支的简述：显示在节点卡片的分支行里
  function conditionBranchDescription(config) {
    const conditions = normalizeConditionList(config?.conditions);
    if (conditions.length > 0) {
      return `${conditions.length} 条条件 · ${config.mode === "any" ? "任一满足" : "全部满足"}`;
    }
    const left = templateShortLabel(config?.left ?? "{{input.message}}");
    const operator = optionLabel("operator", canonicalSelectValue("operator", config?.operator || "contains"));
    const right = typeof config?.right === "object" ? "…" : templateShortLabel(config?.right ?? "");
    return `${left} ${operator} ${right}`.trim();
  }

  function nodeDisplayName(node) {
    const label = cleanText(node?.label);
    if (label) return label;
    return inferredNodeDisplayName(node);
  }

  function inferredNodeDisplayName(node) {
    if (!node) return "未知节点";
    const corpus = nodeCorpus(node);
    const route = ROUTE_RULES.find((rule) => matchesRouteRule(corpus, rule));
    if (node.type === "start") return "开始入口";
    if (node.type === "end") return "结束输出";
    if (node.type === "retriever") return "知识库检索";
    if (node.type === "llm") return route ? `${route.short}回答生成` : "大模型生成";
    if (node.type === "condition") return route ? `${route.short}判断` : "条件判断";
    if (node.type === "tool") return route ? `${route.short}工具调用` : "工具调用";
    if (node.type === "http_request") return route ? `${route.short} HTTP 请求` : "HTTP 请求";
    if (node.type === "parallel") return "并行分发";
    if (node.type === "join") return "结果汇合";
    if (node.type === "variable_aggregator") return "变量聚合器";
    if (node.type === "loop") return "循环判断";
    if (node.type === "loop_back") return "循环回到判断";
    if (node.type === "subgraph") return "子工作流";
    if (node.type === "dynamic") return "动态分配";
    return nodeLabel(node.type);
  }

  function defaultNodeLabel(type) {
    return inferredNodeDisplayName({ id: "", type, label: "", route: "", config: {} });
  }

  const EDGE_CONDITION_LABELS = { true: "IF", false: "ELSE", body: "循环体", exit: "退出循环" };

  function edgeDisplayName(edge) {
    const label = cleanText(edge.label);
    if (label) return label;
    const condition = cleanText(edge.condition);
    return EDGE_CONDITION_LABELS[condition] || condition;
  }

  function fieldShell(label) {
    const shell = document.createElement("label");
    shell.className = "config-field";
    const text = document.createElement("span");
    text.className = "field-label";
    text.textContent = label;
    shell.appendChild(text);
    return shell;
  }

  function controlForField(field, value) {
    if (field.name === "conditions" && field.type === "array") return conditionListControl(field, value);
    if (field.name === "outputSchema") return outputSchemaControl(field, value);
    if (field.name === "writeState" && field.type === "object") return writeStateControl(field, value);
    if (supportsTemplateVariables(field)) return templateControlForField(field, value);
    return baseControlForField(field, value);
  }

  // ============================================================
  // 输出结构约束：可视化字段编辑器
  // 非专业用户按「字段名 + 类型 + 显示名」定义结构化输出，无需手写 JSON Schema；
  // 显示名（title）会直接成为下游变量选择器里的中文标签，取代硬编码映射。
  // ============================================================
  const SCHEMA_FIELD_TYPES = [
    { value: "string", label: "文本" },
    { value: "number", label: "数字" },
    { value: "boolean", label: "布尔" },
    { value: "array", label: "列表" },
    { value: "object", label: "对象" }
  ];

  function normalizeOutputSchema(value) {
    let parsed = value;
    if (typeof parsed === "string") {
      try { parsed = JSON.parse(parsed || "{}"); } catch (error) { parsed = {}; }
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) parsed = {};
    const requiredFields = new Set(Array.isArray(parsed.required) ? parsed.required.map(String) : []);
    const properties = (parsed.properties && typeof parsed.properties === "object" && !Array.isArray(parsed.properties))
      ? parsed.properties
      : {};
    const extras = {};
    Object.keys(parsed).forEach((key) => {
      if (key !== "properties" && key !== "type" && key !== "required") extras[key] = parsed[key];
    });
    const fields = Object.keys(properties).map((name) => {
      const prop = (properties[name] && typeof properties[name] === "object" && !Array.isArray(properties[name]))
        ? properties[name]
        : {};
      return { name, required: requiredFields.has(name), prop: { ...prop } };
    });
    return { fields, extras };
  }

  function buildOutputSchema(model) {
    const named = model.fields.filter((item) => item.name);
    if (named.length === 0) return {};
    const properties = {};
    named.forEach((item) => {
      const prop = { ...item.prop };
      if (typeof prop.description === "string" && !prop.description.trim()) delete prop.description;
      properties[item.name] = prop;
    });
    const schema = { ...model.extras, type: "object", properties };
    const required = named.filter((item) => item.required).map((item) => item.name);
    if (required.length > 0) schema.required = required;
    else delete schema.required;
    return schema;
  }

  function outputSchemaControl(field, value) {
    const container = document.createElement("div");
    container.className = "schema-editor";
    let model = normalizeOutputSchema(value);

    const rows = document.createElement("div");
    rows.className = "schema-rows";
    const raw = document.createElement("textarea");
    raw.className = "code-input";

    const syncRaw = () => { raw.value = formatJson(buildOutputSchema(model)); };
    const commit = () => { syncRaw(); emitControlChange(container); };

    function schemaFieldRow(item, index) {
      const row = document.createElement("div");
      row.className = "schema-row";
      const name = document.createElement("input");
      name.className = "text-input schema-name-input";
      name.type = "text";
      name.placeholder = "字段名（如 sentiment）";
      name.value = item.name;
      name.addEventListener("change", (event) => {
        event.stopPropagation();
        item.name = name.value.trim().replace(/[^a-zA-Z0-9_.]/g, "_");
        name.value = item.name;
        commit();
      });
      const type = document.createElement("select");
      type.className = "select schema-type-select";
      SCHEMA_FIELD_TYPES.forEach((option) => appendOption(type, option.value, option.label));
      type.value = SCHEMA_FIELD_TYPES.some((option) => option.value === item.prop.type) ? item.prop.type : "string";
      type.addEventListener("change", (event) => {
        event.stopPropagation();
        item.prop.type = type.value;
        commit();
      });
      const title = document.createElement("input");
      title.className = "text-input schema-title-input";
      title.type = "text";
      title.placeholder = "显示名（如 情感）";
      title.value = item.prop.title || "";
      title.addEventListener("change", (event) => {
        event.stopPropagation();
        const text = title.value.trim();
        if (text) item.prop.title = text;
        else delete item.prop.title;
        commit();
      });
      const required = document.createElement("label");
      required.className = "schema-required-toggle";
      const requiredInput = document.createElement("input");
      requiredInput.type = "checkbox";
      requiredInput.checked = Boolean(item.required);
      requiredInput.addEventListener("change", (event) => {
        event.stopPropagation();
        item.required = requiredInput.checked;
        commit();
      });
      const requiredText = document.createElement("span");
      requiredText.textContent = "必填";
      required.append(requiredInput, requiredText);
      const description = document.createElement("input");
      description.className = "text-input schema-description-input";
      description.type = "text";
      description.placeholder = "字段说明（帮助模型稳定输出，也会显示在变量图提示里）";
      description.value = item.prop.description || "";
      description.addEventListener("change", (event) => {
        event.stopPropagation();
        const text = description.value.trim();
        if (text) item.prop.description = text;
        else delete item.prop.description;
        commit();
      });
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "icon-btn-sm schema-remove";
      remove.textContent = "×";
      remove.title = "删除字段";
      remove.addEventListener("click", (event) => {
        event.preventDefault();
        model.fields.splice(index, 1);
        renderRows();
        commit();
      });
      row.append(name, type, title, required, description, remove);
      return row;
    }

    function renderRows() {
      rows.innerHTML = "";
      if (model.fields.length === 0) {
        const empty = document.createElement("div");
        empty.className = "schema-empty";
        empty.textContent = "暂无字段 · 模型输出不做结构约束";
        rows.appendChild(empty);
        return;
      }
      model.fields.forEach((item, index) => rows.appendChild(schemaFieldRow(item, index)));
    }

    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm";
    add.textContent = "+ 添加字段";
    add.addEventListener("click", (event) => {
      event.preventDefault();
      model.fields.push({ name: `field_${model.fields.length + 1}`, prop: { type: "string" } });
      renderRows();
      commit();
    });

    const hint = document.createElement("div");
    hint.className = "config-hint";
    hint.textContent = "定义模型必须输出的字段；下游节点的变量下拉会自动出现「结构化 · 显示名」。";

    const advanced = document.createElement("details");
    advanced.className = "condition-advanced-details";
    const advancedSummary = document.createElement("summary");
    advancedSummary.textContent = "高级（直接编辑 JSON Schema）";
    raw.addEventListener("change", (event) => {
      event.stopPropagation();
      let parsed;
      try {
        parsed = JSON.parse(raw.value || "{}");
      } catch (error) {
        toast("JSON Schema 无效", true);
        return;
      }
      model = normalizeOutputSchema(parsed);
      renderRows();
      commit();
    });
    advanced.append(advancedSummary, raw);

    Object.defineProperty(container, "value", {
      get: () => JSON.stringify(buildOutputSchema(model)),
      set: (next) => {
        model = normalizeOutputSchema(next);
        renderRows();
        syncRaw();
      }
    });

    container.append(rows, add, hint, advanced);
    renderRows();
    syncRaw();
    return container;
  }

  function baseControlForField(field, value) {
    const type = field.type || "string";
    if (Array.isArray(field.constraints?.allowedValues) && field.constraints.allowedValues.length > 0) {
      const select = document.createElement("select"); select.className = "select";
      field.constraints.allowedValues.forEach((option) => appendOption(select, String(option), optionLabel(field.name, option)));
      select.value = canonicalSelectValue(field.name, value ?? field.defaultValue ?? field.constraints.allowedValues[0]);
      return select;
    }
    if (type === "boolean") {
      const select = document.createElement("select"); select.className = "select";
      appendOption(select, "true", "是"); appendOption(select, "false", "否");
      select.value = String(Boolean(value)); return select;
    }
    if (type === "integer" || type === "number") {
      const input = document.createElement("input"); input.className = "text-input"; input.type = "number";
      input.value = value ?? field.defaultValue ?? 0;
      if (field.constraints?.min !== undefined) input.min = field.constraints.min;
      if (field.constraints?.max !== undefined) input.max = field.constraints.max;
      return input;
    }
    if (type === "object" || type === "array" || type === "any") {
      const textarea = document.createElement("textarea"); textarea.className = "code-input";
      textarea.value = typeof value === "object" ? formatJson(value) : (value ?? ""); return textarea;
    }
    const textarea = document.createElement("textarea"); textarea.className = "code-input";
    textarea.value = value ?? field.defaultValue ?? ""; return textarea;
  }

  function templateControlForField(field, value) {
    const container = document.createElement("div");
    container.className = "template-control";
    const picker = createVisualVariablePicker(value ?? field.defaultValue ?? "");
    const editor = baseControlForField({ ...field, constraints: { ...(field.constraints || {}), allowedValues: undefined } }, value);
    editor.classList.add("template-editor");
    const hint = document.createElement("div");
    hint.className = "config-hint";
    hint.textContent = "可从变量图选择上游节点输出；需要高级表达式时可直接编辑模板。";

    picker.addEventListener("change", (event) => {
      event.stopPropagation();
      if (picker.value) applyPickedVariable(editor, picker.value, field.name === "prompt" ? "insert" : "replace");
      emitControlChange(container);
    });
    editor.addEventListener("change", (event) => {
      event.stopPropagation();
      syncVisualVariablePicker(picker, editor.value);
      emitControlChange(container);
    });

    Object.defineProperty(container, "value", {
      get: () => editor.value,
      set: (next) => {
        editor.value = next ?? "";
        syncVisualVariablePicker(picker, editor.value);
      }
    });

    container.append(picker, editor, hint);
    syncVisualVariablePicker(picker, editor.value);
    return container;
  }

  function writeStateControl(field, value) {
    const container = document.createElement("div");
    container.className = "write-state-control";
    let entries = normalizeWriteStateEntries(value);
    if (entries.length === 0) entries = [{ key: "", value: "" }];

    const rows = document.createElement("div");
    rows.className = "write-state-rows";
    const add = document.createElement("button");
    add.type = "button";
    add.className = "secondary-btn small";
    add.textContent = "添加状态字段";

    function commit() {
      emitControlChange(container);
    }

    function renderRows() {
      rows.innerHTML = "";
      entries.forEach((entry, index) => {
        const row = document.createElement("div");
        row.className = "write-state-row";

        const keyCell = document.createElement("label");
        keyCell.className = "write-state-cell write-state-key";
        const keyLabel = document.createElement("span");
        keyLabel.textContent = "状态字段";
        const keyInput = document.createElement("input");
        keyInput.className = "text-input";
        keyInput.type = "text";
        keyInput.placeholder = "例如 customerComment";
        keyInput.value = entry.key || "";
        keyInput.addEventListener("change", (event) => {
          event.stopPropagation();
          entries[index].key = keyInput.value.trim();
          commit();
        });
        keyCell.append(keyLabel, keyInput);

        const valueCell = document.createElement("div");
        valueCell.className = "write-state-cell write-state-value";
        const valueLabel = document.createElement("span");
        valueLabel.textContent = "变量来源";
        const valueInput = compactTemplateInput(entry.value || "", (next) => {
          entries[index].value = next;
          if (!entries[index].key) {
            entries[index].key = deriveStateKeyFromVariable(next);
            renderRows();
          }
          commit();
        });
        valueCell.append(valueLabel, valueInput);

        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "icon-btn write-state-remove";
        remove.textContent = "×";
        remove.title = "删除状态字段";
        remove.addEventListener("click", (event) => {
          event.preventDefault();
          event.stopPropagation();
          entries.splice(index, 1);
          if (entries.length === 0) entries.push({ key: "", value: "" });
          renderRows();
          commit();
        });

        row.append(keyCell, valueCell, remove);
        rows.appendChild(row);
      });
    }

    add.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      entries.push({ key: "", value: "" });
      renderRows();
    });

    Object.defineProperty(container, "value", {
      get: () => JSON.stringify(writeStateObjectFromEntries(entries)),
      set: (next) => {
        entries = normalizeWriteStateEntries(next);
        if (entries.length === 0) entries = [{ key: "", value: "" }];
        renderRows();
      }
    });

    container.append(rows, add);
    renderRows();
    return container;
  }

  function normalizeWriteStateEntries(value) {
    let parsed = value;
    if (typeof parsed === "string") {
      try { parsed = JSON.parse(parsed || "{}"); } catch (error) { parsed = {}; }
    }
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) return [];
    return Object.entries(parsed).map(([key, rawValue]) => ({
      key,
      value: valueToEditorText(rawValue)
    }));
  }

  function writeStateObjectFromEntries(entries) {
    const result = {};
    entries.forEach((entry) => {
      const value = valueToEditorText(entry.value).trim();
      const key = (entry.key || deriveStateKeyFromVariable(value)).trim();
      if (!key || !value) return;
      result[key] = value;
    });
    return result;
  }

  function deriveStateKeyFromVariable(value) {
    const text = String(value || "").trim();
    const match = text.match(/^\{\{\s*([^{}]+?)\s*}}$/);
    const path = (match ? match[1] : text).trim();
    const parts = path.split(".").filter(Boolean);
    if (parts[0] === "nodes" && parts.length > 2) return stateKeyFromPath(parts.slice(2));
    if (["input", "state", "lastOutput"].includes(parts[0]) && parts.length > 1) return stateKeyFromPath(parts.slice(1));
    return stateKeyFromPath(parts.length ? [parts[parts.length - 1]] : ["value"]);
  }

  function stateKeyFromPath(parts) {
    const words = parts.join("_").split(/[^A-Za-z0-9]+/).filter(Boolean);
    if (words.length === 0) return "value";
    return words.map((word, index) => {
      const lower = word.charAt(0).toLowerCase() + word.slice(1);
      return index === 0 ? lower : lower.charAt(0).toUpperCase() + lower.slice(1);
    }).join("");
  }

  function conditionListControl(field, value) {
    const container = document.createElement("div");
    container.className = "condition-list-control";
    let conditions = normalizeConditionList(value);
    const rows = document.createElement("div");
    rows.className = "condition-rows";
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm";
    add.textContent = "添加条件";
    const hint = document.createElement("div");
    hint.className = "config-hint";
    hint.textContent = "多条条件会按“复合条件模式”执行；保存时仍转换为 left/operator/right JSON。";

    function renderRows() {
      rows.innerHTML = "";
      if (conditions.length === 0) {
        const empty = document.createElement("div");
        empty.className = "condition-empty";
        empty.textContent = "未添加复合条件，节点会使用上方单条判断左值/右值。";
        rows.appendChild(empty);
        return;
      }
      conditions.forEach((condition, index) => {
        rows.appendChild(conditionRow(condition, index, (patch) => {
          conditions[index] = { ...conditions[index], ...patch };
          emitControlChange(container);
        }, () => {
          conditions.splice(index, 1);
          renderRows();
          emitControlChange(container);
        }));
      });
    }

    add.addEventListener("click", (event) => {
      event.preventDefault();
      conditions.push({ left: "{{input.message}}", operator: "contains", right: "", caseSensitive: false });
      renderRows();
      emitControlChange(container);
    });

    Object.defineProperty(container, "value", {
      get: () => JSON.stringify(conditions),
      set: (next) => {
        conditions = normalizeConditionList(next);
        renderRows();
      }
    });

    container.append(rows, add, hint);
    renderRows();
    return container;
  }

  function conditionRuleControl(config, onChange, onStructureChange = onChange, beforeChange = null) {
    const card = document.createElement("section");
    card.className = "condition-rule-card";
    const currentConditions = normalizeConditionList(config.conditions);
    const ruleMode = currentConditions.length > 0 ? "multi" : "single";

    const head = document.createElement("div");
    head.className = "condition-rule-head";
    const title = document.createElement("div");
    title.className = "condition-rule-title";
    title.textContent = "分支规则";
    const subtitle = document.createElement("div");
    subtitle.className = "condition-rule-subtitle";
    subtitle.textContent = "模型只负责产出内容；这里用固定规则决定 true / false 分支。";
    head.append(title, subtitle);

    const modeSelect = document.createElement("select");
    modeSelect.className = "select condition-rule-mode";
    appendOption(modeSelect, "single", "单条件");
    appendOption(modeSelect, "multi", "多条件");
    modeSelect.value = ruleMode;
    modeSelect.addEventListener("change", (event) => {
      event.stopPropagation();
      if (modeSelect.value === "single") {
        const first = normalizeConditionList(config.conditions)[0] || singleConditionFromConfig(config);
        updateConditionConfig(config, { ...first, conditions: [] }, onChange, beforeChange);
      } else {
        const next = normalizeConditionList(config.conditions);
        if (next.length === 0) next.push(singleConditionFromConfig(config));
        updateConditionConfig(config, { mode: config.mode || "all", conditions: next }, onChange, beforeChange);
      }
      onStructureChange?.();
    });

    const body = document.createElement("div");
    body.className = "condition-rule-body";
    body.appendChild(conditionRuleField("规则类型", modeSelect));
    if (ruleMode === "multi") {
      body.appendChild(renderCompositeConditionRule(config, onChange, beforeChange));
    } else {
      body.appendChild(renderSingleConditionRule(config, onChange, beforeChange));
    }
    body.appendChild(conditionAdvancedDetails(config, onChange, onStructureChange, beforeChange));

    card.append(head, body);
    return card;
  }

  function renderSingleConditionRule(config, onChange, beforeChange = null) {
    const grid = document.createElement("div");
    grid.className = "condition-rule-grid";
    const left = compactTemplateInput(config.left ?? "{{input.message}}", (next) => {
      updateConditionConfig(config, { left: next }, onChange, beforeChange);
    });
    const operator = document.createElement("select");
    operator.className = "select";
    conditionOperatorValues().forEach((option) => appendOption(operator, option, optionLabel("operator", option)));
    operator.value = canonicalSelectValue("operator", config.operator || "contains");
    operator.addEventListener("change", (event) => {
      event.stopPropagation();
      updateConditionConfig(config, { operator: operator.value }, onChange, beforeChange);
    });
    const right = compactTemplateInput(config.right ?? "", (next) => {
      updateConditionConfig(config, { right: parseAnyValue(next) }, onChange, beforeChange);
    }, { hidePickerWhenLiteral: true });
    const caseSensitive = conditionCaseSensitiveControl(Boolean(config.caseSensitive), (next) => {
      updateConditionConfig(config, { caseSensitive: next }, onChange, beforeChange);
    });
    grid.append(
      conditionRuleField("左侧取值", left, "通常选择输入内容、上一步输出，或某个节点的结构化输出。"),
      conditionRuleField("判断方式", operator),
      conditionRuleField("右侧取值", right, "右侧可以是固定值；需要和变量比较时可直接输入模板变量。"),
      conditionRuleField("大小写", caseSensitive)
    );
    return grid;
  }

  function renderCompositeConditionRule(config, onChange, beforeChange = null) {
    const group = document.createElement("div");
    group.className = "condition-rule-group";
    const mode = document.createElement("select");
    mode.className = "select";
    appendOption(mode, "all", "全部满足");
    appendOption(mode, "any", "任一满足");
    mode.value = config.mode === "any" ? "any" : "all";
    mode.addEventListener("change", (event) => {
      event.stopPropagation();
      updateConditionConfig(config, { mode: mode.value }, onChange, beforeChange);
    });
    const list = conditionListControl({ name: "conditions", type: "array" }, config.conditions);
    list.addEventListener("change", (event) => {
      event.stopPropagation();
      updateConditionConfig(config, { conditions: parseControlValue(list.value, "array") }, onChange, beforeChange);
    });
    group.append(
      conditionRuleField("复合方式", mode, "全部满足相当于 AND，任一满足相当于 OR。"),
      list
    );
    return group;
  }

  function conditionAdvancedDetails(config, onChange, onStructureChange, beforeChange = null) {
    const details = document.createElement("details");
    details.className = "condition-advanced-details";
    const summary = document.createElement("summary");
    summary.textContent = "高级配置";
    const raw = document.createElement("textarea");
    raw.className = "code-input";
    raw.value = formatJson(normalizeConditionList(config.conditions));
    raw.addEventListener("change", (event) => {
      event.stopPropagation();
      let parsed;
      try {
        parsed = JSON.parse(raw.value || "[]");
      } catch (error) {
        toast("原始条件 JSON 无效", true);
        return;
      }
      if (!Array.isArray(parsed)) {
        toast("原始条件 JSON 必须是数组", true);
        return;
      }
      updateConditionConfig(config, { conditions: normalizeConditionList(parsed) }, onChange, beforeChange);
      onStructureChange?.();
    });
    const hint = document.createElement("div");
    hint.className = "config-hint";
    hint.textContent = "单条件保存为 left/operator/right；多条件保存为 conditions 数组，运行时仍由后端固定规则判断。";
    details.append(summary, conditionRuleField("原始条件 JSON", raw), hint);
    return details;
  }

  function conditionCaseSensitiveControl(value, onChange) {
    const select = document.createElement("select");
    select.className = "select";
    appendOption(select, "false", "不区分大小写");
    appendOption(select, "true", "区分大小写");
    select.value = String(Boolean(value));
    select.addEventListener("change", (event) => {
      event.stopPropagation();
      onChange(select.value === "true");
    });
    return select;
  }

  function conditionRuleField(label, control, hintText = "") {
    const field = document.createElement("div");
    field.className = "condition-rule-field";
    const text = document.createElement("span");
    text.className = "field-label";
    text.textContent = label;
    field.append(text, control);
    if (hintText) {
      const hint = document.createElement("div");
      hint.className = "config-hint";
      hint.textContent = hintText;
      field.appendChild(hint);
    }
    return field;
  }

  function updateConditionConfig(config, patch, onChange, beforeChange = null) {
    beforeChange?.();
    Object.assign(config, patch);
    onChange?.();
  }

  function singleConditionFromConfig(config) {
    return {
      left: config.left ?? "{{input.message}}",
      operator: canonicalSelectValue("operator", config.operator || "contains"),
      right: config.right ?? "",
      caseSensitive: Boolean(config.caseSensitive)
    };
  }

  function conditionRow(condition, index, onChange, onRemove) {
    const row = document.createElement("div");
    row.className = "condition-row";
    const title = document.createElement("div");
    title.className = "condition-row-title";
    title.textContent = `条件 ${index + 1}`;

    const left = compactTemplateInput(condition.left ?? "{{input.message}}", (next) => onChange({ left: next }));
    const operator = document.createElement("select");
    operator.className = "select";
    conditionOperatorValues().forEach((option) => appendOption(operator, option, optionLabel("operator", option)));
    operator.value = canonicalSelectValue("operator", condition.operator || "contains");
    operator.addEventListener("change", (event) => {
      event.stopPropagation();
      onChange({ operator: operator.value });
    });

    const right = compactTemplateInput(condition.right ?? "", (next) => onChange({ right: parseAnyValue(next) }),
      { hidePickerWhenLiteral: true });
    const caseSensitive = document.createElement("select");
    caseSensitive.className = "select";
    appendOption(caseSensitive, "false", "不区分大小写");
    appendOption(caseSensitive, "true", "区分大小写");
    caseSensitive.value = String(Boolean(condition.caseSensitive));
    caseSensitive.addEventListener("change", (event) => {
      event.stopPropagation();
      onChange({ caseSensitive: caseSensitive.value === "true" });
    });

    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "btn btn-sm btn-danger";
    remove.textContent = "删除条件";
    remove.addEventListener("click", (event) => {
      event.preventDefault();
      onRemove();
    });

    const body = document.createElement("div");
    body.className = "condition-row-body";
    const actions = document.createElement("div");
    actions.className = "condition-row-actions";
    actions.appendChild(remove);
    body.append(
      conditionCell("左值", left, "condition-cell-wide"),
      conditionCell("判断", operator),
      conditionCell("大小写", caseSensitive),
      conditionCell("右值", right, "condition-cell-wide"),
      actions
    );
    row.append(title, body);
    return row;
  }

  function conditionCell(label, control, extraClass = "") {
    const cell = document.createElement("div");
    cell.className = `condition-cell ${extraClass}`.trim();
    const text = document.createElement("span");
    text.textContent = label;
    cell.append(text, control);
    return cell;
  }

  function compactTemplateInput(value, onChange, options = {}) {
    const wrap = document.createElement("div");
    wrap.className = "template-inline";
    const textValue = valueToEditorText(value);
    const showPicker = !(options.hidePickerWhenLiteral && !isExactTemplateReference(textValue));
    const picker = showPicker ? createVisualVariablePicker(value) : null;
    const input = document.createElement("input");
    input.className = "text-input";
    input.type = "text";
    input.value = textValue;
    if (picker) {
      picker.addEventListener("change", (event) => {
        event.stopPropagation();
        if (picker.value) {
          input.value = picker.value;
          onChange(input.value);
        }
      });
    }
    input.addEventListener("change", (event) => {
      event.stopPropagation();
      if (picker) syncVisualVariablePicker(picker, input.value);
      onChange(input.value);
    });
    if (picker) {
      wrap.append(picker, input);
      syncVisualVariablePicker(picker, input.value);
    } else {
      wrap.append(input);
    }
    return wrap;
  }

  function createVisualVariablePicker(selectedValue, options = {}) {
    const container = document.createElement("div");
    container.className = "variable-picker-control";
    const button = document.createElement("button");
    button.type = "button";
    button.className = "variable-picker-button";
    const popover = document.createElement("div");
    popover.className = "variable-picker-popover hidden";
    let currentValue = valueToEditorText(selectedValue);

    const choose = (nextValue) => {
      currentValue = nextValue;
      syncVisualVariablePicker(container, currentValue, options);
      popover.classList.add("hidden");
      emitControlChange(container);
    };

    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      closeVariablePickers(container);
      renderVariablePickerPopover(popover, currentValue, choose, options);
      popover.classList.toggle("hidden");
    });
    document.addEventListener("click", (event) => {
      if (!container.contains(event.target)) popover.classList.add("hidden");
    });

    Object.defineProperty(container, "value", {
      get: () => currentValue,
      set: (next) => {
        currentValue = valueToEditorText(next);
        syncVisualVariablePicker(container, currentValue, options);
      }
    });

    container.append(button, popover);
    syncVisualVariablePicker(container, currentValue, options);
    return container;
  }

  function renderVariablePickerPopover(popover, currentValue, onPick, options = {}) {
    popover.innerHTML = "";
    const title = document.createElement("div");
    title.className = "variable-picker-title";
    title.textContent = "变量图";
    const subtitle = document.createElement("div");
    subtitle.className = "variable-picker-subtitle";
    subtitle.textContent = "按当前节点的上游路径展示可用输出字段，选择后自动写入模板引用。";
    popover.append(title, subtitle);

    const graph = filteredVariableGraph(state.selectedNodeId, options);
    const values = new Set(graph.variables.map((item) => item.value));
    if (isExactTemplateReference(currentValue) && !values.has(currentValue)) {
      const invalid = document.createElement("div");
      invalid.className = "variable-picker-invalid";
      invalid.textContent = `已失效的变量引用：${currentValue}`;
      popover.appendChild(invalid);
    }

    graph.groups.forEach((group) => {
      const items = graph.variables.filter((item) => item.group === group.id);
      const section = document.createElement("section");
      section.className = "variable-picker-group";
      const heading = document.createElement("div");
      heading.className = "variable-picker-group-title";
      heading.textContent = `${group.label}${items.length ? ` · ${items.length}` : ""}`;
      section.appendChild(heading);
      if (items.length === 0) {
        const empty = document.createElement("div");
        empty.className = "variable-picker-empty";
        empty.textContent = group.emptyText || "暂无可用变量";
        section.appendChild(empty);
      } else {
        items.forEach((item) => section.appendChild(variablePickerOption(item, currentValue, onPick)));
      }
      popover.appendChild(section);
    });
  }

  function filteredVariableGraph(currentNodeId, options = {}) {
    const graph = buildVariableGraph(currentNodeId);
    const expectedType = String(options.expectedType || "any");
    const variables = graph.variables.filter((item) => {
      if (options.upstreamOnly && item.group !== "upstream") return false;
      if (expectedType === "any") return true;
      return !item.type || item.type === "any" || item.type === expectedType;
    });
    const groups = options.upstreamOnly
      ? graph.groups.filter((group) => group.id === "upstream")
      : graph.groups;
    return { ...graph, groups, variables };
  }

  function variablePickerOption(item, currentValue, onPick) {
    const option = document.createElement("button");
    option.type = "button";
    option.className = "variable-picker-option";
    if (item.value === currentValue) option.classList.add("active");
    option.title = item.title || item.value;
    option.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      onPick(item.value);
    });
    const main = document.createElement("span");
    main.className = "variable-picker-option-main";
    main.textContent = item.label;
    const meta = document.createElement("span");
    meta.className = "variable-picker-option-meta";
    meta.textContent = item.meta || item.value;
    option.append(main, meta);
    return option;
  }

  function buildVariableGraph(currentNodeId = state.selectedNodeId) {
    const upstreamIds = upstreamNodeIdsFor(currentNodeId);
    const groups = [
      { id: "common", label: "常用变量", emptyText: "暂无常用变量" },
      { id: "state", label: "流程状态", emptyText: "还没有节点写入 workflow state" },
      { id: "upstream", label: "上游节点输出", emptyText: "当前节点还没有可达的上游节点" }
    ];
    const variables = [];
    VARIABLE_PRESETS
      .filter((preset) => !preset.value.includes("nodes.nodeId"))
      .forEach((preset) => variables.push({
        group: "common",
        value: preset.value,
        label: preset.label,
        title: preset.label,
        meta: preset.value
      }));
    inputVariableDescriptors().forEach((descriptor) => variables.push(descriptor));
    stateVariableDescriptors().forEach((descriptor) => variables.push(descriptor));
    state.nodes
      .filter((node) => node.id && node.id !== currentNodeId && upstreamIds.has(node.id))
      .forEach((node) => {
        nodeOutputDescriptors(node).forEach((descriptor) => {
          const value = descriptor.path ? `{{nodes.${node.id}.${descriptor.path}}}` : `{{nodes.${node.id}}}`;
          const metaParts = [node.id];
          if (descriptor.path) metaParts.push(descriptor.path);
          if (descriptor.type) metaParts.push(descriptor.type);
          if (descriptor.required) metaParts.push("必填");
          if (descriptor.description) metaParts.push(descriptor.description);
          variables.push({
            group: "upstream",
            value,
            label: `${nodeDisplayName(node)} / ${descriptor.label}`,
            title: nodeOutputOptionTitle(node, descriptor),
            meta: metaParts.join(" · "),
            nodeId: node.id,
            path: descriptor.path || "",
            type: descriptor.type || "any"
          });
        });
      });
    return { currentNodeId, upstreamIds, groups, variables: dedupeVariableDescriptors(variables) };
  }

  function upstreamNodeIdsFor(nodeId) {
    const result = new Set();
    if (!nodeId) return result;
    const stack = state.edges.filter((edge) => edge.to === nodeId).map((edge) => edge.from);
    while (stack.length > 0) {
      const candidate = stack.pop();
      if (!candidate || result.has(candidate)) continue;
      result.add(candidate);
      state.edges
        .filter((edge) => edge.to === candidate)
        .forEach((edge) => stack.push(edge.from));
    }
    return result;
  }

  function availableVariableDescriptors(currentNodeId = state.selectedNodeId) {
    return buildVariableGraph(currentNodeId).variables;
  }

  function inputVariableDescriptors() {
    const paths = new Set();
    state.nodes.forEach((node) => collectTemplateVariablesFromValue(node.config, (variable) => {
      const name = String(variable || "").trim();
      if (!name.startsWith("input.")) return;
      const path = name.slice("input.".length).trim();
      if (!path || /[\s{}]/.test(path)) return;
      paths.add(path);
    }));
    return Array.from(paths).sort((left, right) => left.localeCompare(right)).map((path) => ({
      group: "common",
      value: `{{input.${path}}}`,
      label: `输入内容 · ${variableLabel(path.split(".").pop())}`,
      title: `输入内容 · ${path}`,
      meta: `input.${path}${inputVariableType(path) ? ` · ${inputVariableType(path)}` : ""}`,
      path,
      type: inputVariableType(path) || "any"
    }));
  }

  function collectTemplateVariablesFromValue(value, onVariable) {
    if (typeof value === "string") {
      const matcher = /\{\{\s*([^{}]+?)\s*}}/g;
      let match;
      while ((match = matcher.exec(value)) !== null) onVariable(match[1]);
      return;
    }
    if (Array.isArray(value)) {
      value.forEach((item) => collectTemplateVariablesFromValue(item, onVariable));
      return;
    }
    if (value && typeof value === "object") {
      Object.values(value).forEach((item) => collectTemplateVariablesFromValue(item, onVariable));
    }
  }

  function inputVariableType(path) {
    const leaf = path.split(".").pop();
    if (["amount", "count", "orderLookupCount"].includes(leaf)) return "number";
    if (["receiptProvided", "hasOrderId", "needsOrderId", "orderLookupReady"].includes(leaf)) return "boolean";
    if (["tools", "history", "orderIds", "currentOrderIds", "recentOrderIds", "referencedOrderIds", "orderToolCalls"].includes(leaf)) return "array";
    return "string";
  }

  function stateVariableDescriptors() {
    const keys = new Set();
    state.nodes.forEach((node) => {
      const writeState = node.config?.writeState;
      if (!writeState || typeof writeState !== "object" || Array.isArray(writeState)) return;
      Object.keys(writeState).filter(Boolean).forEach((key) => keys.add(key));
    });
    return Array.from(keys).sort((left, right) => left.localeCompare(right)).map((key) => ({
      group: "state",
      value: `{{state.${key}}}`,
      label: `流程状态 / ${variableLabel(key)}`,
      title: `流程状态 · ${variableLabel(key)}`,
      meta: `state.${key}`,
      path: key,
      type: "any"
    }));
  }

  function dedupeVariableDescriptors(descriptors) {
    const seen = new Set();
    return descriptors.filter((descriptor) => {
      if (!descriptor?.value || seen.has(descriptor.value)) return false;
      seen.add(descriptor.value);
      return true;
    });
  }

  function syncVisualVariablePicker(picker, value, options = {}) {
    const text = valueToEditorText(value);
    const button = picker.querySelector(".variable-picker-button");
    if (!button) return;
    const descriptor = filteredVariableGraph(state.selectedNodeId, options).variables
      .find((item) => item.value === text);
    button.textContent = descriptor ? descriptor.label : "选择变量";
    button.title = descriptor?.title || (isExactTemplateReference(text) ? text : "从变量图选择上游输出字段");
    button.classList.toggle("has-value", Boolean(descriptor));
    button.classList.toggle("invalid", Boolean(isExactTemplateReference(text) && !descriptor));
  }

  function closeVariablePickers(except = null) {
    document.querySelectorAll(".variable-picker-popover:not(.hidden)").forEach((popover) => {
      if (!except || !except.contains(popover)) popover.classList.add("hidden");
    });
  }

  function applyPickedVariable(editor, pickedValue, mode) {
    if (mode !== "insert") {
      editor.value = pickedValue;
      return;
    }
    const start = typeof editor.selectionStart === "number" ? editor.selectionStart : editor.value.length;
    const end = typeof editor.selectionEnd === "number" ? editor.selectionEnd : start;
    const before = editor.value.slice(0, start);
    const after = editor.value.slice(end);
    editor.value = `${before}${pickedValue}${after}`;
    const nextCursor = start + pickedValue.length;
    if (typeof editor.setSelectionRange === "function") {
      editor.focus();
      editor.setSelectionRange(nextCursor, nextCursor);
    }
  }

  function isExactTemplateReference(value) {
    return /^\{\{[^{}]+}}$/.test(String(value || "").trim());
  }

  function createVariableSelect(selectedValue) {
    const select = document.createElement("select");
    select.className = "select template-select";
    appendOption(select, "", "选择变量来源");
    appendOptionGroup(select, "常用变量", VARIABLE_PRESETS);
    const nodeOptions = nodeVariableOptions();
    if (nodeOptions.length > 0) appendOptionGroup(select, "节点输出", nodeOptions);
    appendOption(select, "__custom__", "自定义模板 / 固定值");
    syncVariableSelect(select, selectedValue);
    return select;
  }

  function nodeVariableOptions() {
    const selected = state.selectedNodeId;
    return state.nodes
      .filter((node) => node.id && node.id !== selected)
      .flatMap((node) => {
        return nodeOutputDescriptors(node).map((descriptor) => ({
          value: descriptor.path ? `{{nodes.${node.id}.${descriptor.path}}}` : `{{nodes.${node.id}}}`,
          label: nodeOutputOptionLabel(node, descriptor),
          title: nodeOutputOptionTitle(node, descriptor)
        }));
      });
  }

  function nodeOutputOptionLabel(node, descriptor) {
    return `${nodeDisplayName(node)} · ${descriptor.label}`;
  }

  function nodeOutputOptionTitle(node, descriptor) {
    const path = descriptor.path ? ` · ${descriptor.path}` : "";
    const required = descriptor.required ? " · 必填" : "";
    const description = descriptor.description ? ` · ${descriptor.description}` : "";
    return `${nodeDisplayName(node)} · ${descriptor.title || descriptor.label}${path}${required}${description}`;
  }

  function nodeOutputDescriptors(node) {
    if (node.type === "variable_aggregator") return variableAggregatorOutputDescriptors(node);
    const baseFields = DEFAULT_NODE_OUTPUT_FIELDS[node.type] || ["__self__", "output", "result"];
    const descriptors = baseFields.map(defaultNodeOutputDescriptor);
    outputSchemaFieldDescriptors(node.config?.outputSchema)
      .forEach((descriptor) => descriptors.push(descriptor));
    return dedupeDescriptors(descriptors);
  }

  function variableAggregatorOutputDescriptors(node) {
    const mode = node.config?.mode || "single";
    const descriptors = [{ path: "", label: "完整输出", type: "object" }];
    if (mode !== "groups") {
      descriptors.push({ path: "output", label: "聚合输出", type: node.config?.outputType || "any" });
      return descriptors;
    }
    (Array.isArray(node.config?.groups) ? node.config.groups : []).forEach((group) => {
      const key = String(group?.key || "").trim();
      if (!key) return;
      descriptors.push({
        path: `${key}.output`,
        label: group.label || key,
        type: group.outputType || "any"
      });
    });
    return descriptors;
  }

  function defaultNodeOutputDescriptor(field) {
    if (field === "__self__") return { path: "", label: "完整输出", type: "object" };
    const stringLike = ["message", "query", "retrievedContext", "answer", "text", "output", "errorMessage", "body"];
    const arrayLike = ["results", "outputs", "toolCalls", "orderIds", "artifacts"];
    const numberLike = ["statusCode", "durationMs", "itemCount"];
    const booleanLike = ["succeeded", "result", "fallback"];
    const objectLike = ["headers", "json", "parsed", "primary", "printPreview", "inputs"];
    const type = stringLike.includes(field) ? "string"
      : arrayLike.includes(field) ? "array"
        : numberLike.includes(field) ? "number"
          : booleanLike.includes(field) ? "boolean"
            : objectLike.includes(field) ? "object" : "any";
    return { path: field, label: variableLabel(field), type };
  }

  function outputSchemaFieldDescriptors(outputSchema) {
    const schemaObject = parseSchemaObject(outputSchema);
    const properties = schemaObject.properties;
    if (!properties || typeof properties !== "object" || Array.isArray(properties)) return [];
    const requiredFields = new Set(Array.isArray(schemaObject.required) ? schemaObject.required.map(String) : []);
    return Object.keys(properties)
      .filter(Boolean)
      .flatMap((field) => schemaFieldDescriptors(field, properties[field], requiredFields.has(field)));
  }

  function parseSchemaObject(outputSchema) {
    if (!outputSchema) return {};
    if (typeof outputSchema === "object" && !Array.isArray(outputSchema)) return outputSchema;
    if (typeof outputSchema === "string") {
      try {
        const parsed = JSON.parse(outputSchema || "{}");
        return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? parsed : {};
      } catch (error) {
        return {};
      }
    }
    return {};
  }

  function schemaFieldDescriptors(path, schema, required = false) {
    const labelKey = path.split(".").pop();
    const isObject = schema && typeof schema === "object" && !Array.isArray(schema);
    // 用户在输出结构约束里填的「显示名」（schema.title）优先；硬编码映射只作兜底
    const display = isObject && typeof schema.title === "string" && schema.title.trim()
      ? schema.title.trim()
      : schemaFieldLabel(labelKey);
    const description = isObject && typeof schema.description === "string" && schema.description.trim()
      ? schema.description.trim()
      : "";
    const descriptors = [{
      path: `parsed.${path}`,
      label: `结构化 · ${display}`,
      title: `结构化输出 · ${display}`,
      type: isObject && typeof schema.type === "string" ? schema.type : "any",
      required,
      description
    }];
    if (isObject && schema.properties
      && typeof schema.properties === "object" && !Array.isArray(schema.properties)) {
      const childRequiredFields = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
      Object.keys(schema.properties)
        .filter(Boolean)
        .forEach((child) => descriptors.push(
          ...schemaFieldDescriptors(`${path}.${child}`, schema.properties[child], childRequiredFields.has(child))));
    }
    return descriptors;
  }

  function schemaFieldLabel(name) {
    return VARIABLE_LABELS[name] || FIELD_LABELS[name] || name;
  }

  function dedupeDescriptors(descriptors) {
    const seen = new Set();
    return descriptors.filter((descriptor) => {
      const key = descriptor.path;
      if (seen.has(key)) return false;
      seen.add(key);
      return true;
    });
  }

  function appendOptionGroup(select, label, options) {
    const group = document.createElement("optgroup");
    group.label = label;
    options.forEach((option) => appendOption(group, option.value, option.label, option.title));
    select.appendChild(group);
  }

  function syncVariableSelect(select, value) {
    const text = valueToEditorText(value);
    const selectedOption = Array.from(select.querySelectorAll("option")).find((option) => option.value === text);
    select.value = selectedOption ? text : "__custom__";
    const activeOption = selectedOption || select.querySelector("option[value='__custom__']");
    select.title = activeOption?.title || activeOption?.textContent || "";
  }

  function normalizeConditionList(value) {
    let raw = value;
    if (typeof raw === "string") {
      const trimmed = raw.trim();
      if (!trimmed) return [];
      try { raw = JSON.parse(trimmed); } catch (error) { return []; }
    }
    if (!Array.isArray(raw)) return [];
    return raw
      .filter((item) => item && typeof item === "object" && !Array.isArray(item))
      .map((item) => ({
        left: item.left ?? "{{input.message}}",
        operator: canonicalSelectValue("operator", item.operator || "contains"),
        right: item.right ?? "",
        caseSensitive: Boolean(item.caseSensitive)
      }));
  }

  function conditionOperatorValues() {
    return ["equals", "notEquals", "contains", "notContains", "startsWith", "endsWith", "exists", "notExists", "greaterThan", "lessThan"];
  }

  function canonicalSelectValue(fieldName, value) {
    if (fieldName !== "operator") return String(value ?? "");
    const normalized = String(value ?? "").toLowerCase();
    const aliases = {
      notequals: "notEquals", notcontains: "notContains", startswith: "startsWith", endswith: "endsWith",
      notexists: "notExists", greaterthan: "greaterThan", lessthan: "lessThan"
    };
    return aliases[normalized] || String(value ?? "");
  }

  function emitControlChange(control) {
    control.dispatchEvent(new Event("change", { bubbles: true }));
  }

  function valueToEditorText(value) {
    return typeof value === "object" && value !== null ? formatJson(value) : String(value ?? "");
  }

  function textControl(value) {
    const input = document.createElement("input");
    input.className = "text-input"; input.type = "text"; input.value = value || "";
    return input;
  }

  function selectNodeControl(value) {
    const select = document.createElement("select"); select.className = "select";
    state.nodes.forEach((node) => appendOption(select, node.id, `${nodeDisplayName(node)}（${node.id}）`));
    select.value = value; return select;
  }

  function parseControlValue(value, type) {
    if (type === "integer") return Number.parseInt(value || "0", 10);
    if (type === "number") return Number(value || 0);
    if (type === "boolean") return value === "true";
    if (type === "object") return parseJsonInput(value, {});
    if (type === "array") return parseJsonInput(value, []);
    if (type === "any") return parseAnyValue(value);
    return value;
  }

  function parseAnyValue(value) {
    const trimmed = String(value || "").trim();
    if (!trimmed) return "";
    try { return JSON.parse(trimmed); } catch (error) { return value; }
  }

  function parseJsonInput(value, fallback) {
    try { return JSON.parse(value || "{}"); } catch (error) { toast("JSON 输入无效", true); return fallback; }
  }

  // ============================================================
  // 工具函数
  // ============================================================
  function setWorkflowStatus(value) {
    const exact = { DRAFT: "草稿", PUBLISHED: "已发布", Draft: "草稿", Valid: "校验通过", Invalid: "校验未过", Ran: "已运行", "Run failed": "运行失败" };
    const label = exact[value] != null ? exact[value]
      : String(value).replace(/\bDRAFT\b/g, "草稿").replace(/\bPUBLISHED\b/g, "已发布").replace(/\bRun\b/g, "运行")
          .replace(/\(loaded\)/g, "（已载入）").replace(/\(rollback\)/g, "（回滚）");
    els.workflowStatus.textContent = label;
    const cls = /已发布|PUBLISHED/.test(label) ? "badge-ok" : /失败|未过/.test(label) ? "badge-err" : /运行/.test(label) ? "badge-run" : "badge-draft";
    els.workflowStatus.className = `badge ${cls}`;
  }

  function statusCn(status) {
    if (!status) return "";
    const map = { SUCCEEDED: "成功", SUCCESS: "成功", FAILED: "失败", ERROR: "错误", RUNNING: "运行中", PENDING: "等待中",
      NOT_EXECUTED: "未执行", DRAFT: "草稿", PUBLISHED: "已发布", INDEXED: "已索引", PENDING_INDEX: "待索引", SKIPPED: "已跳过" };
    return map[String(status).toUpperCase()] || status;
  }

  function colorForType(type) { return NODE_COLORS[type] || "var(--node-flow)"; }
  function iconSvg(type) {
    const path = NODE_ICONS[type] || NODE_ICONS.end_default;
    return `<svg viewBox="0 0 24 24" aria-hidden="true">${path}</svg>`;
  }

  function appendOption(select, value, label, title = label) {
    const option = document.createElement("option");
    option.value = value; option.textContent = label;
    option.title = title;
    select.appendChild(option);
  }

  function formatJson(value) { return JSON.stringify(value ?? {}, null, 2); }
  function safeStringify(value) {
    try { return JSON.stringify(value ?? {}); } catch (error) { return String(value ?? ""); }
  }
  function short(value) { return value ? String(value).slice(0, 8) : ""; }
  function truncate(value, n) { const s = String(value || ""); return s.length > n ? s.slice(0, n) + "…" : s; }
  function nullableText(value) {
    const text = String(value ?? "").trim();
    return text ? text : null;
  }

  // 在响应对象中递归寻找首个可读文本（answer/text/content/...）
  function deepFindText(value, depth = 0) {
    if (depth > 5 || value == null) return "";
    if (typeof value === "string") return value.trim() && value.length > 1 ? value : "";
    if (typeof value !== "object") return "";
    const preferred = ["answer", "output", "text", "content", "message", "result", "response", "reply"];
    for (const key of preferred) {
      if (typeof value[key] === "string" && value[key].trim()) return value[key];
    }
    for (const key of preferred) {
      if (value[key] && typeof value[key] === "object") { const nested = deepFindText(value[key], depth + 1); if (nested) return nested; }
    }
    for (const key of Object.keys(value)) {
      if (["runId", "conversationId", "stepId", "nodeName", "exportId", "artifactId", "fileName",
        "format", "mimeType", "sha256", "downloadUrl", "expiresAt", "printPreview"].includes(key)) continue;
      const nested = deepFindText(value[key], depth + 1);
      if (nested) return nested;
    }
    return "";
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;").replace(/'/g, "&#039;");
  }

  function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === "function") return window.CSS.escape(value);
    return String(value).replace(/"/g, "\\\"");
  }

  function toCamel(value) { return value.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase()); }
  function clamp(value, min, max) { return Math.max(min, Math.min(max, value)); }

  function toast(message, isError = false) {
    window.clearTimeout(state.toastTimer);
    els.toast.textContent = message;
    els.toast.classList.toggle("error", isError);
    els.toast.classList.add("visible");
    state.toastTimer = window.setTimeout(() => els.toast.classList.remove("visible"), 2600);
  }
