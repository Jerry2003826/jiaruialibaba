"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};


  const fallbackSchemas = [
    { type: "start", displayName: "Start", configFields: [] },
    { type: "retriever", displayName: "Retriever", configFields: [{ name: "topK", type: "integer", defaultValue: 3 }] },
    { type: "llm", displayName: "LLM", configFields: [{ name: "prompt", type: "string", defaultValue: "Answer using this context: {{context}}\nInput: {{input}}" }, { name: "model", type: "string", defaultValue: "" }] },
    { type: "tool", displayName: "Tool", configFields: [{ name: "toolName", type: "string", defaultValue: "getCurrentTime" }] },
    { type: "condition", displayName: "Condition", configFields: [{ name: "left", type: "string", defaultValue: "{{input}}" }, { name: "operator", type: "string", defaultValue: "contains" }, { name: "right", type: "any", defaultValue: "" }] },
    { type: "parallel", displayName: "Parallel", configFields: [] },
    { type: "join", displayName: "Join", configFields: [] },
    { type: "loop", displayName: "Loop", configFields: [{ name: "maxIterations", type: "integer", defaultValue: 10 }, { name: "left", type: "string", defaultValue: "{{input.count}}" }, { name: "operator", type: "string", defaultValue: "greaterthan" }, { name: "right", type: "string", defaultValue: "0" }] },
    { type: "loop_back", displayName: "Loop Back", configFields: [] },
    { type: "subgraph", displayName: "Subgraph", configFields: [{ name: "definitionId", type: "string", defaultValue: "" }, { name: "version", type: "integer", defaultValue: null }] },
    { type: "dynamic", displayName: "Dynamic", configFields: [{ name: "itemsFrom", type: "string", defaultValue: "{{input.tools}}" }, { name: "action", type: "string", defaultValue: "tool" }] },
    { type: "end", displayName: "End", configFields: [] }
  ];

  const NODE_COLORS = {
    start: "var(--node-start)", end: "var(--node-end)",
    retriever: "var(--node-rag)", llm: "var(--node-llm)", tool: "var(--node-tool)",
    condition: "var(--node-flow)", parallel: "var(--node-flow)", join: "var(--node-flow)",
    loop: "var(--node-flow)", loop_back: "var(--node-flow)", subgraph: "var(--node-flow)", dynamic: "var(--node-flow)"
  };

  const NODE_LABELS = {
    start: "开始", retriever: "检索", llm: "大模型", tool: "工具",
    condition: "条件", parallel: "并行", join: "汇合", loop: "循环",
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
    llm: '<path d="M12 3l1.8 4.2L18 9l-4.2 1.8L12 15l-1.8-4.2L6 9l4.2-1.8z"/><path d="M18 15l.9 2.1L21 18l-2.1.9L18 21l-.9-2.1L15 18l2.1-.9z"/>',
    tool: '<path d="M14 6a3 3 0 0 1 4 4l-8 8-4 1 1-4z"/>',
    condition: '<circle cx="6" cy="6" r="2.4"/><circle cx="18" cy="6" r="2.4"/><circle cx="12" cy="18" r="2.4"/><path d="M6 8v3a3 3 0 0 0 3 3h6a3 3 0 0 0 3-3V8"/><path d="M12 14v2"/>',
    parallel: '<path d="M12 4v6M12 10c0 3-5 2-5 6M12 10c0 3 5 2 5 6"/><circle cx="7" cy="18" r="2"/><circle cx="17" cy="18" r="2"/>',
    join: '<path d="M7 4c0 4 5 3 5 6s-5 2-5 6M17 4c0 4-5 3-5 6"/><circle cx="12" cy="18" r="2"/>',
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
    definitionId: null,
    definitionVersion: null,
    definitionStatus: null,
    savedDefinitions: [],
    lastRunId: null,
    toastTimer: null,
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
    routePanelCollapsed: false
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
    topK: "检索条数", prompt: "提示词模板", model: "模型名称", toolName: "调用工具",
    left: "判断左值", operator: "判断条件", right: "判断右值", maxIterations: "最大循环次数",
    itemsFrom: "动态数据来源", action: "执行动作", definitionId: "子工作流", version: "子工作流版本",
    timeoutMs: "超时时间", retryCount: "重试次数", idempotent: "可安全重试"
  };

  const VARIABLE_LABELS = {
    message: "用户消息", input: "输入内容", context: "检索上下文", output: "输出结果", answer: "回答内容",
    conversationId: "会话编号", history: "上下文历史", currentOrderIds: "本轮订单号",
    recentOrderIds: "历史订单号", referencedOrderIds: "实际查询订单号", orderLookupReady: "订单查询就绪",
    orderLookupCount: "订单查询数量", orderToolCalls: "订单工具调用", user_query: "用户查询内容",
    orderId: "订单号", customerName: "客户姓名", status: "订单状态", amount: "订单金额",
    currency: "币种", paid: "是否支付", carrier: "承运商", trackingNumber: "运单号",
    estimatedDelivery: "预计送达", latestEvent: "最新动态", nextAction: "下一步动作",
    toolCalls: "工具调用", retrievedContext: "引用知识", runId: "运行编号", found: "是否找到"
  };

  function configFieldLabel(name) { return FIELD_LABELS[name] || variableLabel(name); }
  function variableLabel(name) { return VARIABLE_LABELS[name] || FIELD_LABELS[name] || `自定义变量（${name}）`; }

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
    VARIABLE_LABELS
  };
  AgentWorkbench.variableLabel = variableLabel;
  AgentWorkbench.configFieldLabel = configFieldLabel;
  window.AgentWorkbench.state = state;
  window.AgentWorkbench.constants = AgentWorkbench.constants;
  window.AgentWorkbench.variableLabel = variableLabel;
  window.AgentWorkbench.configFieldLabel = configFieldLabel;

  function nodeSummary(node) {
    if (cleanText(node.route)) return cleanText(node.route);
    if (node.type === "llm") return String(node.config.prompt || "提示词").slice(0, 54);
    if (node.type === "tool") return String(node.config.toolName || "getCurrentTime");
    if (node.type === "retriever") return `topK ${node.config.topK || 3}`;
    if (node.type === "condition") return `${node.config.operator || "contains"}`;
    if (node.type === "subgraph") return node.config.definitionId ? `定义 ${node.config.definitionId}` : "子图";
    if (node.type === "loop") return `最多 ${node.config.maxIterations || 10} 次`;
    if (node.type === "dynamic") return String(node.config.itemsFrom || "items").slice(0, 40);
    return nodeLabel(node.type);
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
    if (node.type === "parallel") return "并行分发";
    if (node.type === "join") return "结果汇合";
    if (node.type === "loop") return "循环判断";
    if (node.type === "loop_back") return "循环回到判断";
    if (node.type === "subgraph") return "子工作流";
    if (node.type === "dynamic") return "动态分配";
    return nodeLabel(node.type);
  }

  function defaultNodeLabel(type) {
    return inferredNodeDisplayName({ id: "", type, label: "", route: "", config: {} });
  }

  function edgeDisplayName(edge) {
    return cleanText(edge.label) || cleanText(edge.condition);
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
    const type = field.type || "string";
    if (type === "boolean") {
      const select = document.createElement("select"); select.className = "select";
      appendOption(select, "true", "true"); appendOption(select, "false", "false");
      select.value = String(Boolean(value)); return select;
    }
    if (type === "integer" || type === "number") {
      const input = document.createElement("input"); input.className = "text-input"; input.type = "number";
      input.value = value ?? field.defaultValue ?? 0;
      if (field.constraints?.min !== undefined) input.min = field.constraints.min;
      if (field.constraints?.max !== undefined) input.max = field.constraints.max;
      return input;
    }
    if (type === "object" || type === "any") {
      const textarea = document.createElement("textarea"); textarea.className = "code-input";
      textarea.value = typeof value === "object" ? formatJson(value) : (value ?? ""); return textarea;
    }
    const textarea = document.createElement("textarea"); textarea.className = "code-input";
    textarea.value = value ?? field.defaultValue ?? ""; return textarea;
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

  function appendOption(select, value, label) {
    const option = document.createElement("option");
    option.value = value; option.textContent = label;
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
      if (key === "runId" || key === "conversationId" || key === "stepId" || key === "nodeName") continue;
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
