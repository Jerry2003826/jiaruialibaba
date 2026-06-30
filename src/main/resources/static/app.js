(() => {
  "use strict";

  // ============================================================
  // API 端点（后端契约，保持不变）
  // ============================================================
  const API = {
    health: "/api/health",
    devToken: "/api/auth/dev-token",
    nodeSchemas: "/api/workflows/node-schemas",
    validateWorkflow: "/api/workflows/validate",
    previewGraph: "/api/workflows/preview-graph",
    runWorkflow: "/api/workflows/run",
    definitions: "/api/workflows/definitions",
    definitionRevisions: (id) => `/api/workflows/definitions/${encodeURIComponent(id)}/revisions`,
    rollbackDefinition: (id, version) => `/api/workflows/definitions/${encodeURIComponent(id)}/rollback/${version}`,
    workflowRuns: (id) => `/api/workflows/runs?definitionId=${encodeURIComponent(id)}&page=0&size=20`,
    publishDefinition: (id) => `/api/workflows/definitions/${encodeURIComponent(id)}/publish`,
    workflowRunGraph: (runId) => `/api/workflows/runs/${encodeURIComponent(runId)}/graph`,
    runSteps: (runId) => `/api/runs/${encodeURIComponent(runId)}/steps`,
    runs: "/api/runs",
    tools: "/api/tools",
    mcpServers: "/api/tools/mcp/servers",
    chat: "/api/chat",
    chatStream: "/api/chat/stream",
    toolChat: "/api/agent/tool-chat",
    saveDocument: "/api/rag/documents",
    listDocuments: "/api/rag/documents",
    deleteDocument: (id) => `/api/rag/documents/${encodeURIComponent(id)}`,
    ragChat: "/api/rag/chat"
  };

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
    chatHistories: { chat: [], agent: [], rag: [] },
    activeRunCardId: null
  };

  const els = {};
  let authToken = null; // 每次 API/SSE 调用的 Bearer，由 bootstrapAuth() 在本地/demo 模式填充

  const viewRoutes = {
    workflow: () => {},
    chat: () => renderChat(),
    library: () => void loadDocuments(),
    runs: () => void loadRuns(),
    tools: () => void loadTools()
  };

  // ============================================================
  // 控制器（保留全局名，前端资源测试依赖）
  // ============================================================
  class WorkflowCanvasController {
    init() {
      cacheElements();
      bindNavigation();
      bindRuntimeChip();
      bindWorkflowActions();
      bindCanvasInteractions();
      bindRunDrawer();
      bindChat();
      bindLibrary();
      bindRuns();
      bindTools();
      resetWorkflow();
      renderAll();
      renderChat();
      void loadInitialData();
    }
    exportDefinition() { return buildWorkflowDefinition(); }
    getState() {
      return { nodes: state.nodes, edges: state.edges, selectedNodeId: state.selectedNodeId, definitionId: state.definitionId };
    }
  }
  window.WorkflowCanvasController = new WorkflowCanvasController();
  document.addEventListener("DOMContentLoaded", () => window.WorkflowCanvasController.init());

  // ============================================================
  // 元素缓存
  // ============================================================
  function cacheElements() {
    const ids = [
      "runtime-chip", "runtime-dot", "runtime-status", "runtime-details",
      "definition-name", "workflow-status", "definition-select", "load-definition", "toggle-history",
      "save-definition", "run-workflow", "wf-more", "wf-menu", "new-workflow", "validate-workflow",
      "insert-loop-template", "publish-definition",
      "palette", "palette-collapse", "node-palette",
      "workflow-canvas", "canvas-world", "edge-layer", "node-layer", "canvas-empty",
      "zoom-out", "zoom-label", "zoom-in", "zoom-fit",
      "node-inspector", "inspector-title", "inspector-close", "inspector-empty", "inspector-form",
      "edge-section", "add-edge", "edge-list", "delete-node",
      "definition-history", "refresh-definition-history", "history-close", "revision-list", "workflow-run-list",
      "run-drawer", "run-drawer-toggle", "run-handle-status", "input-mode-seg", "run-input-form",
      "workflow-input", "run-workflow-drawer", "refresh-run-graph", "run-result", "trace-steps", "run-output",
      "chat-mode-pill", "chat-mode-seg", "chat-transcript", "chat-hint", "chat-message", "stream-chat", "send-chat",
      "refresh-documents", "rag-hint", "document-title", "document-content", "save-document",
      "rag-message", "ask-rag", "rag-output", "document-list",
      "refresh-runs", "run-list", "run-detail",
      "refresh-tools", "tool-list", "mcp-server-list", "toast"
    ];
    ids.forEach((id) => { els[toCamel(id)] = document.getElementById(id); });
  }

  // ============================================================
  // 导航
  // ============================================================
  function bindNavigation() {
    document.querySelectorAll("[data-view]").forEach((button) => {
      button.addEventListener("click", () => {
        const view = button.dataset.view;
        document.querySelectorAll("[data-view]").forEach((item) => item.classList.toggle("active", item === button));
        document.querySelectorAll("[data-view-panel]").forEach((panel) => {
          panel.classList.toggle("active", panel.dataset.viewPanel === view);
        });
        viewRoutes[view]?.();
      });
    });
  }

  function bindRuntimeChip() {
    els.runtimeChip?.addEventListener("click", () => {
      const hidden = els.runtimeDetails.classList.toggle("hidden");
      els.runtimeChip.setAttribute("aria-expanded", String(!hidden));
    });
    document.addEventListener("click", (event) => {
      if (els.runtimeDetails && !els.runtimeDetails.classList.contains("hidden")
        && !els.runtimeDetails.contains(event.target) && !els.runtimeChip.contains(event.target)) {
        els.runtimeDetails.classList.add("hidden");
        els.runtimeChip.setAttribute("aria-expanded", "false");
      }
    });
  }

  // ============================================================
  // 工作流动作绑定
  // ============================================================
  function bindWorkflowActions() {
    els.definitionSelect?.addEventListener("change", () => {
      if (els.definitionSelect.value) void loadDefinition(els.definitionSelect.value);
    });
    els.loadDefinition?.addEventListener("click", () => {
      if (els.definitionSelect.value) void loadDefinition(els.definitionSelect.value);
    });
    els.saveDefinition?.addEventListener("click", () => void saveDefinition());
    els.runWorkflow?.addEventListener("click", () => { openRunDrawer(); void runWorkflow(); });
    els.runWorkflowDrawer?.addEventListener("click", () => void runWorkflow());
    els.refreshRunGraph?.addEventListener("click", () => { if (state.lastRunId) void refreshRunTrace(state.lastRunId); });

    // 溢出菜单
    els.wfMore?.addEventListener("click", (event) => {
      event.stopPropagation();
      const hidden = els.wfMenu.classList.toggle("hidden");
      els.wfMore.setAttribute("aria-expanded", String(!hidden));
    });
    document.addEventListener("click", (event) => {
      if (els.wfMenu && !els.wfMenu.classList.contains("hidden")
        && !els.wfMenu.contains(event.target) && !els.wfMore.contains(event.target)) {
        els.wfMenu.classList.add("hidden");
      }
    });
    const closeMenu = () => els.wfMenu?.classList.add("hidden");
    els.newWorkflow?.addEventListener("click", () => {
      closeMenu(); resetWorkflow(); renderAll(); void loadDefinitionHistory(); toast("已新建工作流");
    });
    els.validateWorkflow?.addEventListener("click", () => { closeMenu(); void validateWorkflow(); });
    els.insertLoopTemplate?.addEventListener("click", () => { closeMenu(); insertLoopTemplate(); });
    els.publishDefinition?.addEventListener("click", () => { closeMenu(); void publishDefinition(); });

    // 检查器
    els.inspectorClose?.addEventListener("click", () => closeInspector());
    els.deleteNode?.addEventListener("click", deleteSelectedNode);
    els.addEdge?.addEventListener("click", addEdgeFromSelected);

    // 历史抽屉
    els.toggleHistory?.addEventListener("click", () => toggleHistory());
    els.historyClose?.addEventListener("click", () => els.definitionHistory.classList.add("hidden"));
    els.refreshDefinitionHistory?.addEventListener("click", () => void loadDefinitionHistory());

    // 面板折叠
    els.paletteCollapse?.addEventListener("click", () => els.palette.classList.toggle("collapsed"));
  }

  // ============================================================
  // 初始化数据
  // ============================================================
  async function loadInitialData() {
    await bootstrapAuth();
    await Promise.allSettled([loadHealth(), loadSchemas(), loadDefinitions()]);
  }

  async function bootstrapAuth() {
    try {
      const data = await requestJson(API.devToken);
      authToken = data?.token ?? null;
      if (!authToken) toast("未返回开发令牌，API 调用可能未授权", true);
    } catch (error) {
      authToken = null;
      toast(`认证不可用：${error.message}。请通过你的 IdP 登录后再使用工作台。`, true);
    }
  }

  function authHeaders(extra = {}) {
    const headers = { ...extra };
    if (authToken) headers.Authorization = `Bearer ${authToken}`;
    return headers;
  }

  // ============================================================
  // 运行时健康
  // ============================================================
  async function loadHealth() {
    try {
      const data = await requestJson(API.health);
      state.health = data;
      const modelLabel = data.modelConfigured ? data.model : "未配置模型";
      els.runtimeStatus.textContent = `${healthLabel(data.status)} · ${modelLabel}`;
      const dotClass = data.status && /up|ok|healthy/i.test(data.status)
        ? (data.modelConfigured ? "ok" : "warn") : "warn";
      els.runtimeDot.className = `runtime-dot ${dotClass}`;
      renderRuntimeDetails(data);
      updateRagHint(data);
    } catch (error) {
      els.runtimeStatus.textContent = "运行时不可用";
      els.runtimeDot.className = "runtime-dot err";
      els.runtimeDetails.innerHTML = `<div class="rt-row"><span class="rt-key">错误</span><span class="rt-val">${escapeHtml(error.message)}</span></div>`;
    }
  }

  function healthLabel(status) {
    if (!status) return "未知";
    if (/up|ok|healthy/i.test(status)) return "在线";
    return status;
  }

  function renderRuntimeDetails(health) {
    const rows = [
      ["工作流运行时", `${health.workflowRuntime} · 须发布=${boolCn(health.workflowRequirePublishedForRun)}`],
      ["严格模式", `${boolCn(health.strictMode)} · 回退=${boolCn(health.fallbackEnabled)}`],
      ["向量库", `${boolCn(health.vectorStoreConfigured)} · 检索=${health.ragRetriever}`],
      ["已索引文档", `${health.indexedDocumentCount}`],
      ["MCP", boolCn(health.mcpEnabled)]
    ];
    els.runtimeDetails.innerHTML = rows.map(([k, v]) =>
      `<div class="rt-row"><span class="rt-key">${escapeHtml(k)}</span><span class="rt-val">${escapeHtml(String(v))}</span></div>`
    ).join("");
  }

  function boolCn(value) { return value ? "是" : "否"; }

  function updateRagHint(health) {
    if (!els.ragHint) return;
    if (health.vectorStoreConfigured && health.indexedDocumentCount === 0) {
      els.ragHint.textContent = "DashVector 已就绪，但 PostgreSQL 中暂无已索引文档。请重新点击「保存文档」，否则 RAG 检索会返回空上下文。";
      els.ragHint.classList.remove("hidden");
      return;
    }
    if (!health.strictMode && !health.modelConfigured) {
      els.ragHint.textContent = "模型未配置，当前处于 demo 回退模式。配置 DashScope 并开启 strict 可走真实阿里栈。";
      els.ragHint.classList.remove("hidden");
      return;
    }
    els.ragHint.classList.add("hidden");
  }

  // ============================================================
  // 节点 schema / 面板
  // ============================================================
  async function loadSchemas() {
    try {
      const schemas = await requestJson(API.nodeSchemas);
      if (Array.isArray(schemas) && schemas.length > 0) state.schemas = schemas;
    } catch (error) {
      toast(error.message, true);
    }
    renderPalette();
  }

  function renderPalette() {
    if (!els.nodePalette) return;
    els.nodePalette.innerHTML = "";
    state.schemas.forEach((schema) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "palette-button";
      button.innerHTML = `
        <span class="palette-ico" style="background:${colorForType(schema.type)}">${iconSvg(schema.type)}</span>
        <span>${escapeHtml(nodeLabel(schema))}</span>
        <span class="plus">+</span>`;
      button.addEventListener("click", () => addNode(schema.type));
      els.nodePalette.appendChild(button);
    });
  }

  // ============================================================
  // 工作流定义 CRUD
  // ============================================================
  async function loadDefinitions() {
    try {
      const definitions = await requestJson(API.definitions);
      state.savedDefinitions = Array.isArray(definitions) ? definitions : [];
      els.definitionSelect.innerHTML = "";
      appendOption(els.definitionSelect, "", "选择已保存的工作流…");
      state.savedDefinitions.forEach((d) => appendOption(els.definitionSelect, d.definitionId, `${d.name} · v${d.version}`));
      if (state.definitionId) els.definitionSelect.value = state.definitionId;
    } catch (error) {
      state.savedDefinitions = [];
      els.definitionSelect.innerHTML = "";
      appendOption(els.definitionSelect, "", "暂无已保存定义");
    }
  }

  async function loadDefinition(definitionId) {
    try {
      const definition = await requestJson(`${API.definitions}/${encodeURIComponent(definitionId)}`);
      state.definitionId = definition.definitionId;
      state.definitionVersion = definition.version;
      state.definitionStatus = definition.status;
      els.definitionName.value = definition.name;
      hydrateWorkflow(definition.workflowDefinition);
      loadCanvasPositions();
      renderAll();
      zoomToFit();
      setWorkflowStatus(`${definition.status || "DRAFT"} v${definition.version}`);
      await loadDefinitionHistory();
      toast(`已载入「${definition.name}」`);
    } catch (error) {
      toast(error.message, true);
    }
  }

  function resetWorkflow() {
    state.definitionId = null;
    state.definitionVersion = null;
    state.definitionStatus = null;
    state.lastRunId = null;
    state.connectSourceId = null;
    state.selectedNodeId = null;
    state.nodes = [
      { id: "start", type: "start", config: {} },
      { id: "retriever_1", type: "retriever", config: { topK: 3 } },
      { id: "llm_1", type: "llm", config: { prompt: "Answer using this context: {{context}}\nInput: {{input}}" } },
      { id: "end", type: "end", config: {} }
    ];
    state.edges = [
      { from: "start", to: "retriever_1", condition: "" },
      { from: "retriever_1", to: "llm_1", condition: "" },
      { from: "llm_1", to: "end", condition: "" }
    ];
    state.positions = new Map([
      ["start", { x: 80, y: 90 }],
      ["retriever_1", { x: 360, y: 90 }],
      ["llm_1", { x: 640, y: 90 }],
      ["end", { x: 920, y: 90 }]
    ]);
    loadCanvasPositions();
    els.definitionName.value = "智能体工作流";
    if (els.workflowInput) els.workflowInput.value = DEFAULT_RUN_INPUT;
    if (els.runOutput) els.runOutput.textContent = "{}";
    if (els.runResult) { els.runResult.className = "result-card empty-result"; els.runResult.textContent = "运行后在这里查看友好结果与每一步轨迹。"; }
    if (els.traceSteps) els.traceSteps.innerHTML = "";
    closeInspector();
    els.definitionHistory?.classList.add("hidden");
    setWorkflowStatus("Draft");
    renderDefinitionHistory([], []);
  }

  function hydrateWorkflow(definition) {
    state.nodes = (definition.nodes || []).map((node) => ({ id: node.id, type: node.type, config: { ...(node.config || {}) } }));
    state.edges = (definition.edges || []).map((edge) => ({ from: edge.from, to: edge.to, condition: edge.condition || "" }));
    state.positions = new Map();
    state.nodes.forEach((node, index) => {
      const col = index % 4, row = Math.floor(index / 4);
      state.positions.set(node.id, { x: 80 + col * 280, y: 90 + row * 170 });
    });
    loadCanvasPositions();
    state.selectedNodeId = null;
    closeInspector();
  }

  // ---------- 画布坐标持久化 ----------
  function canvasPositionsStorageKey() { return `${CANVAS_POSITIONS_KEY_PREFIX}${state.definitionId || "draft"}`; }

  function saveCanvasPositions() {
    const payload = {};
    state.positions.forEach((position, nodeId) => { payload[nodeId] = position; });
    try { window.localStorage.setItem(canvasPositionsStorageKey(), JSON.stringify(payload)); } catch (error) { /* 忽略隐私/配额 */ }
  }

  function loadCanvasPositions() {
    try {
      const raw = window.localStorage.getItem(canvasPositionsStorageKey());
      if (!raw) return;
      const saved = JSON.parse(raw);
      Object.entries(saved || {}).forEach(([nodeId, position]) => {
        if (state.nodes.some((n) => n.id === nodeId) && position && typeof position.x === "number") {
          state.positions.set(nodeId, { x: position.x, y: position.y });
        }
      });
    } catch (error) { /* 忽略损坏数据 */ }
  }

  // ============================================================
  // 渲染总入口
  // ============================================================
  function renderAll() {
    renderPalette();
    renderNodes();
    renderEdges();
    renderInspector();
    applyCanvasTransform();
    updateCanvasEmpty();
  }

  function updateCanvasEmpty() {
    els.canvasEmpty?.classList.toggle("hidden", state.nodes.length > 0);
  }

  // ============================================================
  // 画布：节点 / 连线 / 变换
  // ============================================================
  function nodeLabel(typeOrSchema) {
    const type = typeof typeOrSchema === "string" ? typeOrSchema : typeOrSchema.type;
    const fallback = typeof typeOrSchema === "object" && typeOrSchema ? (typeOrSchema.displayName || type) : type;
    return NODE_LABELS[type] || fallback;
  }

  function renderNodes() {
    els.nodeLayer.innerHTML = "";
    state.nodes.forEach((node) => {
      const position = state.positions.get(node.id) || { x: 80, y: 80 };
      const element = document.createElement("article");
      element.className = "canvas-node";
      element.dataset.nodeId = node.id;
      element.style.left = `${position.x}px`;
      element.style.top = `${position.y}px`;
      element.classList.toggle("selected", state.selectedNodeId === node.id);
      element.classList.toggle("connecting", state.connectSourceId === node.id);
      const color = colorForType(node.type);
      element.innerHTML = `
        <div class="node-accent" style="background:${color}"></div>
        <div class="node-main">
          <span class="node-ico" style="background:${color}">${iconSvg(node.type)}</span>
          <div class="node-meta">
            <div class="node-type">${escapeHtml(nodeLabel(node.type))}</div>
            <div class="node-id">${escapeHtml(node.id)}</div>
            <div class="node-summary">${escapeHtml(nodeSummary(node))}</div>
          </div>
        </div>
        ${node.type === "end" ? "" : '<button class="node-port" type="button" aria-label="从此节点连线">+</button>'}`;
      bindNodeInteractions(element, node);
      els.nodeLayer.appendChild(element);
    });
  }

  function bindNodeInteractions(element, node) {
    let down = null;
    let moved = false;
    element.addEventListener("pointerdown", (event) => {
      if (event.target.closest(".node-port")) return;
      event.stopPropagation();
      down = { x: event.clientX, y: event.clientY };
      moved = false;
      const startPos = state.positions.get(node.id) || { x: 0, y: 0 };
      element.setPointerCapture(event.pointerId);
      const move = (ev) => {
        const dx = (ev.clientX - down.x) / state.view.scale;
        const dy = (ev.clientY - down.y) / state.view.scale;
        if (!moved && Math.hypot(ev.clientX - down.x, ev.clientY - down.y) > 4) {
          moved = true; element.classList.add("dragging");
        }
        if (moved) {
          const next = { x: Math.round(startPos.x + dx), y: Math.round(startPos.y + dy) };
          state.positions.set(node.id, next);
          element.style.left = `${next.x}px`;
          element.style.top = `${next.y}px`;
          renderEdges();
        }
      };
      const up = () => {
        element.removeEventListener("pointermove", move);
        element.removeEventListener("pointerup", up);
        element.removeEventListener("pointercancel", up);
        element.classList.remove("dragging");
        if (moved) { saveCanvasPositions(); } else { selectOrConnectNode(node.id); }
      };
      element.addEventListener("pointermove", move);
      element.addEventListener("pointerup", up);
      element.addEventListener("pointercancel", up);
    });

    const port = element.querySelector(".node-port");
    port?.addEventListener("click", (event) => {
      event.stopPropagation();
      state.connectSourceId = state.connectSourceId === node.id ? null : node.id;
      renderNodes();
      toast(state.connectSourceId ? `从「${node.id}」开始连线，点击目标节点完成` : "已取消连线");
    });
  }

  function renderEdges() {
    const svg = els.edgeLayer;
    // 保留 <defs>，清除旧路径
    svg.querySelectorAll(".edge-path, .edge-label").forEach((n) => n.remove());
    state.edges.forEach((edge) => {
      const from = portWorld(edge.from, "right");
      const to = portWorld(edge.to, "left");
      if (!from || !to) return;
      const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
      const mid = Math.max(50, Math.abs(to.x - from.x) / 2);
      path.setAttribute("d", `M ${from.x} ${from.y} C ${from.x + mid} ${from.y}, ${to.x - mid} ${to.y}, ${to.x} ${to.y}`);
      path.setAttribute("class", edge.condition ? "edge-path conditional" : "edge-path");
      path.setAttribute("marker-end", "url(#arrow)");
      svg.appendChild(path);
      if (edge.condition) {
        const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
        label.setAttribute("x", String((from.x + to.x) / 2));
        label.setAttribute("y", String((from.y + to.y) / 2 - 8));
        label.setAttribute("text-anchor", "middle");
        label.setAttribute("class", "edge-label");
        label.textContent = edge.condition;
        svg.appendChild(label);
      }
    });
  }

  // 节点端口的世界坐标（与缩放无关，使用未缩放布局尺寸 offsetWidth/Height）
  function portWorld(nodeId, side) {
    const position = state.positions.get(nodeId);
    if (!position) return null;
    const element = els.nodeLayer.querySelector(`[data-node-id="${cssEscape(nodeId)}"]`);
    const width = element ? element.offsetWidth : 196;
    const height = element ? element.offsetHeight : 70;
    return { x: side === "right" ? position.x + width : position.x, y: position.y + height / 2 };
  }

  function applyCanvasTransform() {
    const { scale, panX, panY } = state.view;
    els.canvasWorld.style.transform = `translate(${panX}px, ${panY}px) scale(${scale})`;
    if (els.zoomLabel) els.zoomLabel.textContent = `${Math.round(scale * 100)}%`;
  }

  function bindCanvasInteractions() {
    const canvas = els.workflowCanvas;
    // 平移（在背景按下拖动）
    canvas.addEventListener("pointerdown", (event) => {
      if (event.target.closest(".canvas-node") || event.target.closest(".canvas-controls")) return;
      const origin = { x: event.clientX, y: event.clientY, panX: state.view.panX, panY: state.view.panY };
      canvas.classList.add("panning");
      canvas.setPointerCapture(event.pointerId);
      if (state.connectSourceId) { state.connectSourceId = null; renderNodes(); }
      const move = (ev) => {
        state.view.panX = origin.panX + (ev.clientX - origin.x);
        state.view.panY = origin.panY + (ev.clientY - origin.y);
        applyCanvasTransform();
      };
      const up = () => {
        canvas.removeEventListener("pointermove", move);
        canvas.removeEventListener("pointerup", up);
        canvas.removeEventListener("pointercancel", up);
        canvas.classList.remove("panning");
      };
      canvas.addEventListener("pointermove", move);
      canvas.addEventListener("pointerup", up);
      canvas.addEventListener("pointercancel", up);
    });
    // 缩放（滚轮朝光标）
    canvas.addEventListener("wheel", (event) => {
      event.preventDefault();
      const rect = canvas.getBoundingClientRect();
      const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12;
      zoomAt(factor, event.clientX - rect.left, event.clientY - rect.top);
    }, { passive: false });

    els.zoomIn?.addEventListener("click", () => zoomAtCenter(1.18));
    els.zoomOut?.addEventListener("click", () => zoomAtCenter(1 / 1.18));
    els.zoomLabel?.addEventListener("click", () => { state.view = { scale: 1, panX: 40, panY: 30 }; applyCanvasTransform(); });
    els.zoomFit?.addEventListener("click", () => zoomToFit());
  }

  function zoomAt(factor, cx, cy) {
    const oldScale = state.view.scale;
    const newScale = clamp(oldScale * factor, ZOOM_MIN, ZOOM_MAX);
    if (newScale === oldScale) return;
    const worldX = (cx - state.view.panX) / oldScale;
    const worldY = (cy - state.view.panY) / oldScale;
    state.view.scale = newScale;
    state.view.panX = cx - worldX * newScale;
    state.view.panY = cy - worldY * newScale;
    applyCanvasTransform();
  }

  function zoomAtCenter(factor) {
    const rect = els.workflowCanvas.getBoundingClientRect();
    zoomAt(factor, rect.width / 2, rect.height / 2);
  }

  function zoomToFit() {
    if (state.nodes.length === 0) return;
    let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
    state.nodes.forEach((node) => {
      const p = state.positions.get(node.id); if (!p) return;
      const el = els.nodeLayer.querySelector(`[data-node-id="${cssEscape(node.id)}"]`);
      const w = el ? el.offsetWidth : 196, h = el ? el.offsetHeight : 80;
      minX = Math.min(minX, p.x); minY = Math.min(minY, p.y);
      maxX = Math.max(maxX, p.x + w); maxY = Math.max(maxY, p.y + h);
    });
    if (!isFinite(minX)) return;
    const rect = els.workflowCanvas.getBoundingClientRect();
    const pad = 60;
    const drawerH = els.runDrawer && !els.runDrawer.classList.contains("collapsed") ? rect.height * 0.5 : 46;
    const availH = rect.height - drawerH;
    const scale = clamp(Math.min((rect.width - pad * 2) / (maxX - minX || 1), (availH - pad * 2) / (maxY - minY || 1)), ZOOM_MIN, 1.2);
    state.view.scale = scale;
    state.view.panX = pad - minX * scale + Math.max(0, (rect.width - pad * 2 - (maxX - minX) * scale) / 2);
    state.view.panY = pad - minY * scale;
    applyCanvasTransform();
  }

  // ============================================================
  // 检查器
  // ============================================================
  function openInspector() {
    els.definitionHistory?.classList.add("hidden");
    els.nodeInspector?.classList.remove("hidden");
  }
  function closeInspector() {
    els.nodeInspector?.classList.add("hidden");
    state.selectedNodeId = null;
    renderNodes();
  }
  function toggleHistory() {
    const willOpen = els.definitionHistory.classList.contains("hidden");
    if (willOpen) { els.nodeInspector?.classList.add("hidden"); els.definitionHistory.classList.remove("hidden"); void loadDefinitionHistory(); }
    else els.definitionHistory.classList.add("hidden");
  }

  function renderInspector() {
    const node = findSelectedNode();
    if (!node) {
      els.inspectorEmpty.classList.remove("hidden");
      els.inspectorForm.classList.add("hidden");
      els.edgeSection.classList.add("hidden");
      els.deleteNode.disabled = true;
      return;
    }
    openInspector();
    els.inspectorTitle.textContent = `${nodeLabel(node.type)} 节点`;
    els.inspectorEmpty.classList.add("hidden");
    els.inspectorForm.classList.remove("hidden");
    els.edgeSection.classList.remove("hidden");
    els.deleteNode.disabled = node.type === "start" || node.type === "end";
    els.inspectorForm.innerHTML = "";

    const idField = fieldShell("节点 ID");
    const idInput = textControl(node.id);
    idInput.addEventListener("change", () => renameNode(node.id, idInput.value.trim()));
    idField.appendChild(idInput);
    els.inspectorForm.appendChild(idField);

    const schema = schemaForType(node.type);
    const typeField = fieldShell("类型");
    const typeValue = document.createElement("input");
    typeValue.className = "text-input";
    typeValue.value = nodeLabel(node.type);
    typeValue.disabled = true;
    typeField.appendChild(typeValue);
    els.inspectorForm.appendChild(typeField);

    (schema.configFields || []).forEach((field) => {
      const shell = fieldShell(configFieldLabel(field.name));
      let control;
      if (node.type === "subgraph" && field.name === "definitionId") {
        control = subgraphDefinitionControl(node.config.definitionId || "");
        control.addEventListener("change", () => {
          node.config.definitionId = control.value; node.config.version = null;
          renderInspector(); renderNodes();
        });
      } else if (node.type === "subgraph" && field.name === "version") {
        control = document.createElement("select");
        control.className = "select";
        appendOption(control, "", "最新");
        if (node.config.version != null && node.config.version !== "") {
          appendOption(control, String(node.config.version), `v${node.config.version}`);
          control.value = String(node.config.version);
        }
        void populateSubgraphVersionOptions(control, node.config.definitionId, node.config.version);
        control.addEventListener("change", () => { node.config.version = control.value ? Number.parseInt(control.value, 10) : null; renderNodes(); });
      } else {
        control = controlForField(field, node.config[field.name]);
        control.addEventListener("change", () => { node.config[field.name] = parseControlValue(control.value, field.type); renderNodes(); });
      }
      shell.appendChild(control);
      els.inspectorForm.appendChild(shell);
    });

    renderEdgeEditor(node);
  }

  function subgraphDefinitionControl(selectedId) {
    const select = document.createElement("select");
    select.className = "select";
    appendOption(select, "", "选择定义");
    state.savedDefinitions.forEach((d) => appendOption(select, d.definitionId, `${d.name} (${d.definitionId})`));
    select.value = selectedId || "";
    return select;
  }

  async function populateSubgraphVersionOptions(select, definitionId, selectedVersion) {
    if (!definitionId) return;
    try {
      const revisions = await requestJson(API.definitionRevisions(definitionId));
      select.innerHTML = "";
      appendOption(select, "", "最新");
      revisions.forEach((r) => appendOption(select, String(r.version), `v${r.version} (${r.status})`));
      if (selectedVersion != null && selectedVersion !== "") select.value = String(selectedVersion);
    } catch (error) { toast(error.message, true); }
  }

  // 仅展示选中节点相关的连线，友好可编辑
  function renderEdgeEditor(node) {
    els.edgeList.innerHTML = "";
    const related = state.edges.map((edge, index) => ({ edge, index })).filter(({ edge }) => edge.from === node.id || edge.to === node.id);
    if (related.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "该节点暂无连线 · 悬停节点拖出端口可连线";
      els.edgeList.appendChild(empty);
      return;
    }
    related.forEach(({ edge, index }) => {
      const row = document.createElement("div");
      row.className = "edge-row";
      const from = selectNodeControl(edge.from);
      const arrow = document.createElement("span");
      arrow.className = "edge-arrow"; arrow.textContent = "→";
      const to = selectNodeControl(edge.to);
      const fromNode = state.nodes.find((n) => n.id === edge.from);
      const condition = edgeConditionControl(fromNode, edge.condition || "");
      condition.classList.add("edge-condition");
      const remove = document.createElement("button");
      remove.type = "button"; remove.className = "edge-remove"; remove.textContent = "删除连线";
      from.addEventListener("change", () => { edge.from = from.value; renderInspector(); renderEdges(); });
      to.addEventListener("change", () => { edge.to = to.value; renderInspector(); renderEdges(); });
      condition.addEventListener("change", () => { edge.condition = condition.value.trim(); renderEdges(); });
      remove.addEventListener("click", () => { state.edges.splice(index, 1); renderInspector(); renderEdges(); });
      row.append(from, arrow, to, condition, remove);
      els.edgeList.appendChild(row);
    });
  }

  function addEdgeFromSelected() {
    const node = findSelectedNode();
    if (!node) return;
    const target = state.nodes.find((n) => n.id !== node.id);
    if (!target) return;
    state.edges.push({ from: node.id, to: target.id, condition: "" });
    renderInspector(); renderEdges();
  }

  function edgeConditionControl(fromNode, value) {
    if (fromNode?.type === "loop") {
      const select = document.createElement("select"); select.className = "select";
      appendOption(select, "body", "body"); appendOption(select, "exit", "exit"); appendOption(select, "", "(无)");
      select.value = value || ""; return select;
    }
    if (fromNode?.type === "condition") {
      const select = document.createElement("select"); select.className = "select";
      appendOption(select, "true", "true"); appendOption(select, "false", "false"); appendOption(select, "", "(无)");
      select.value = value || ""; return select;
    }
    const input = textControl(value);
    input.placeholder = "条件（可空）";
    return input;
  }

  // ============================================================
  // 节点操作
  // ============================================================
  function insertLoopTemplate() {
    ensureStartEndNodes();
    const loopId = uniqueNodeId("loop");
    const bodyId = uniqueNodeId("body_tool");
    const loopBackId = uniqueNodeId("loop_back");
    const afterId = uniqueNodeId("after_tool");
    const loopSchema = schemaForType("loop");
    const toolSchema = schemaForType("tool");
    state.nodes.push(
      { id: loopId, type: "loop", config: defaultConfig(loopSchema) },
      { id: bodyId, type: "tool", config: defaultConfig(toolSchema) },
      { id: loopBackId, type: "loop_back", config: {} },
      { id: afterId, type: "tool", config: defaultConfig(toolSchema) }
    );
    const startNode = state.nodes.find((n) => n.type === "start");
    const endNode = state.nodes.find((n) => n.type === "end");
    if (startNode && !state.edges.some((e) => e.from === startNode.id && e.to === loopId)) {
      state.edges.push({ from: startNode.id, to: loopId, condition: "" });
    }
    state.edges.push(
      { from: loopId, to: bodyId, condition: "body" },
      { from: bodyId, to: loopBackId, condition: "" },
      { from: loopBackId, to: loopId, condition: "" },
      { from: loopId, to: afterId, condition: "exit" },
      { from: afterId, to: endNode.id, condition: "" }
    );
    const baseY = 90 + Math.floor(state.nodes.length / 4) * 170;
    state.positions.set(loopId, { x: 200, y: baseY });
    state.positions.set(bodyId, { x: 480, y: baseY });
    state.positions.set(loopBackId, { x: 760, y: baseY });
    state.positions.set(afterId, { x: 480, y: baseY + 170 });
    state.selectedNodeId = loopId;
    saveCanvasPositions();
    renderAll();
    zoomToFit();
    toast("已插入循环模板 — 可点「校验拓扑」检查");
  }

  function ensureStartEndNodes() {
    if (!state.nodes.some((n) => n.type === "start")) state.nodes.unshift({ id: uniqueNodeId("start"), type: "start", config: {} });
    if (!state.nodes.some((n) => n.type === "end")) state.nodes.push({ id: uniqueNodeId("end"), type: "end", config: {} });
  }

  function uniqueNodeId(base) {
    let index = 1, id = `${base}_${index}`;
    while (state.nodes.some((n) => n.id === id)) { index += 1; id = `${base}_${index}`; }
    return id;
  }

  function addNode(type) {
    const baseId = type.replace(/[^a-zA-Z0-9_]/g, "_") || "node";
    const id = uniqueNodeId(baseId);
    const node = { id, type, config: defaultConfig(schemaForType(type)) };
    state.nodes.push(node);
    // 落在视口中心的世界坐标
    const rect = els.workflowCanvas.getBoundingClientRect();
    const wx = (rect.width / 2 - state.view.panX) / state.view.scale - 98;
    const wy = (rect.height / 2 - state.view.panY) / state.view.scale - 35;
    state.positions.set(id, { x: Math.round(wx + (state.nodes.length % 3) * 24), y: Math.round(wy + (state.nodes.length % 3) * 24) });
    state.selectedNodeId = id;
    saveCanvasPositions();
    renderAll();
    toast(`已添加「${nodeLabel(type)}」节点`);
  }

  function selectOrConnectNode(nodeId) {
    if (state.connectSourceId && state.connectSourceId !== nodeId) {
      const duplicate = state.edges.some((e) => e.from === state.connectSourceId && e.to === nodeId);
      if (!duplicate) { state.edges.push({ from: state.connectSourceId, to: nodeId, condition: "" }); toast("已连线"); }
      state.connectSourceId = null;
    }
    state.selectedNodeId = nodeId;
    renderNodes();
    renderEdges();
    renderInspector();
  }

  function deleteSelectedNode() {
    const node = findSelectedNode();
    if (!node || node.type === "start" || node.type === "end") return;
    state.nodes = state.nodes.filter((n) => n.id !== node.id);
    state.edges = state.edges.filter((e) => e.from !== node.id && e.to !== node.id);
    state.positions.delete(node.id);
    state.selectedNodeId = null;
    saveCanvasPositions();
    closeInspector();
    renderAll();
  }

  function renameNode(oldId, newId) {
    if (!newId || oldId === newId || state.nodes.some((n) => n.id === newId)) { renderInspector(); return; }
    const node = state.nodes.find((n) => n.id === oldId);
    if (!node) return;
    node.id = newId;
    const position = state.positions.get(oldId);
    state.positions.delete(oldId);
    state.positions.set(newId, position || { x: 80, y: 80 });
    state.edges.forEach((e) => { if (e.from === oldId) e.from = newId; if (e.to === oldId) e.to = newId; });
    state.selectedNodeId = newId;
    saveCanvasPositions();
    renderAll();
  }

  // ============================================================
  // 校验 / 预览 / 保存 / 发布 / 运行
  // ============================================================
  async function validateWorkflow() {
    await runCommand({
      request: () => requestJson(API.validateWorkflow, { method: "POST", body: { workflowDefinition: buildWorkflowDefinition() } }),
      onSuccess: async (response) => {
        setWorkflowStatus(response.valid ? "Valid" : "Invalid");
        toast(response.valid ? "工作流校验通过" : "工作流校验未通过", !response.valid);
      },
      onError: (error) => { setWorkflowStatus("Invalid"); toast(error.message, true); }
    });
  }

  async function saveDefinition() {
    const body = {
      name: els.definitionName.value.trim() || "智能体工作流",
      description: "来自智能体工作台",
      workflowDefinition: buildWorkflowDefinition()
    };
    const url = state.definitionId ? `${API.definitions}/${encodeURIComponent(state.definitionId)}` : API.definitions;
    const method = state.definitionId ? "PUT" : "POST";
    await runCommand({
      request: () => requestJson(url, { method, body }),
      onSuccess: async (response) => {
        state.definitionId = response.definitionId;
        state.definitionVersion = response.version;
        state.definitionStatus = response.status;
        setWorkflowStatus(`${response.status || "DRAFT"} v${response.version}`);
        saveCanvasPositions();
        await loadDefinitions();
        els.definitionSelect.value = response.definitionId;
        await loadDefinitionHistory();
        toast("工作流已保存");
      },
      onError: (error) => toast(error.message, true)
    });
  }

  async function publishDefinition() {
    if (!state.definitionId) { toast("发布前请先保存工作流", true); return; }
    await runCommand({
      request: () => requestJson(API.publishDefinition(state.definitionId), { method: "POST" }),
      onSuccess: async (response) => {
        state.definitionVersion = response.version;
        state.definitionStatus = response.status;
        setWorkflowStatus(`${response.status || "PUBLISHED"} v${response.version}`);
        await loadDefinitions();
        await loadDefinitionHistory();
        toast("工作流已发布");
      },
      onError: (error) => toast(error.message, true)
    });
  }

  async function runWorkflow() {
    syncFormToJson();
    const input = parseJsonInput(els.workflowInput.value, {});
    setRunStatus("运行中…");
    els.runResult.className = "result-card empty-result";
    els.runResult.textContent = "正在运行…";
    await runCommand({
      request: () => requestJson(API.runWorkflow, { method: "POST", body: { workflowDefinition: buildWorkflowDefinition(), input } }),
      outputEl: els.runOutput,
      onSuccess: async (response) => {
        state.lastRunId = response.runId;
        setWorkflowStatus(response.runId ? `Run ${response.runId.slice(0, 8)}` : "Ran");
        renderRunResult(response);
        await refreshRunTrace(response.runId);
        setRunStatus(`完成 · ${response.runId ? response.runId.slice(0, 8) : ""}`);
        await loadDefinitionHistory();
        toast("工作流运行完成");
      },
      onError: (error) => {
        setWorkflowStatus("Run failed");
        setRunStatus("运行失败");
        els.runResult.className = "result-card is-error";
        els.runResult.textContent = `运行失败：${error.message}`;
        toast(error.message, true);
      }
    });
  }

  function setRunStatus(text) { if (els.runHandleStatus) els.runHandleStatus.textContent = text; }

  // 友好结果：从响应中提取可读答案
  function renderRunResult(response) {
    const answer = deepFindText(response);
    els.runResult.className = "result-card";
    if (answer) {
      els.runResult.innerHTML = `<div class="result-label">结果</div><div class="result-answer"></div><div class="result-meta-row"></div>`;
      els.runResult.querySelector(".result-answer").textContent = answer;
      const meta = els.runResult.querySelector(".result-meta-row");
      if (response.runId) meta.appendChild(makeBadge(`运行 ${response.runId.slice(0, 8)}`, "badge-soft"));
      if (response.status) meta.appendChild(makeBadge(String(response.status), "badge-ok"));
    } else {
      els.runResult.innerHTML = `<div class="result-label">结果</div><div class="result-answer">运行完成（无文本输出，详见下方原始 JSON 与运行步骤）。</div>`;
    }
  }

  // ============================================================
  // 运行轨迹
  // ============================================================
  async function refreshRunTrace(runId) {
    if (!runId) return;
    try {
      const [steps, graph] = await Promise.allSettled([
        requestJson(API.runSteps(runId)),
        requestJson(API.workflowRunGraph(runId))
      ]);
      if (steps.status === "fulfilled") {
        renderTraceSteps(els.traceSteps, steps.value, graph.status === "fulfilled" ? graph.value : null);
      }
      if (graph.status === "fulfilled") applyGraphStatuses(graph.value);
    } catch (error) { toast(error.message, true); }
  }

  function applyGraphStatuses(graph) {
    const statusByNode = new Map();
    (graph.nodes || []).forEach((node) => {
      statusByNode.set(node.id, node.status || "NOT_EXECUTED");
      (node.children || []).forEach((child) => { if (child.id) statusByNode.set(child.id, child.status || "NOT_EXECUTED"); });
    });
    document.querySelectorAll(".canvas-node").forEach((element) => {
      element.classList.remove("status-success", "status-failed", "status-running");
      const status = statusByNode.get(element.dataset.nodeId);
      if (!status) return;
      const cls = statusClass(status);
      if (cls === "success") element.classList.add("status-success");
      else if (cls === "failed") element.classList.add("status-failed");
      else if (cls === "running") element.classList.add("status-running");
      element.title = `状态：${status}`;
    });
  }

  function statusClass(status) {
    const s = String(status || "").toUpperCase();
    if (/SUCC|OK|DONE|COMPLETE/.test(s)) return "success";
    if (/FAIL|ERROR|ABORT/.test(s)) return "failed";
    if (/RUN|PROGRESS|PENDING/.test(s)) return "running";
    return "other";
  }

  // ============================================================
  // 定义历史
  // ============================================================
  async function loadDefinitionHistory() {
    if (!state.definitionId) { renderDefinitionHistory([], []); return; }
    const [revisionsResult, runsResult] = await Promise.allSettled([
      requestJson(API.definitionRevisions(state.definitionId)),
      requestJson(API.workflowRuns(state.definitionId))
    ]);
    const revisions = revisionsResult.status === "fulfilled" ? revisionsResult.value : [];
    const runs = runsResult.status === "fulfilled" ? (runsResult.value?.content || []) : [];
    renderDefinitionHistory(revisions, runs);
  }

  function renderDefinitionHistory(revisions, runs) {
    els.revisionList.innerHTML = "";
    els.workflowRunList.innerHTML = "";
    if (!state.definitionId) {
      els.revisionList.appendChild(emptyDiv("保存工作流后查看版本"));
      els.workflowRunList.appendChild(emptyDiv("保存工作流后查看运行"));
      return;
    }
    if (!revisions || revisions.length === 0) els.revisionList.appendChild(emptyDiv("暂无版本"));
    else revisions.forEach((revision) => {
      const row = document.createElement("div");
      row.className = "history-row";
      const meta = document.createElement("div");
      meta.innerHTML = `<strong>v${revision.version}</strong> <span class="history-row-meta">${escapeHtml(statusCn(revision.status))} · ${escapeHtml(revision.updatedAt || revision.createdAt || "")}</span>`;
      const actions = document.createElement("div");
      actions.className = "history-row-actions";
      const loadButton = document.createElement("button");
      loadButton.type = "button"; loadButton.textContent = "载入";
      loadButton.addEventListener("click", () => loadRevisionOntoCanvas(revision));
      const rollbackButton = document.createElement("button");
      rollbackButton.type = "button"; rollbackButton.textContent = "回滚";
      rollbackButton.addEventListener("click", () => void rollbackDefinitionVersion(revision.version));
      actions.append(loadButton, rollbackButton);
      row.append(meta, actions);
      els.revisionList.appendChild(row);
    });

    if (!runs || runs.length === 0) els.workflowRunList.appendChild(emptyDiv("该定义暂无运行记录"));
    else runs.forEach((run) => {
      const row = document.createElement("div");
      row.className = "history-row clickable";
      row.innerHTML = `<div><strong>${escapeHtml(statusCn(run.status))}</strong><div class="history-row-meta">v${run.definitionVersion} · ${escapeHtml(short(run.runId))} · ${escapeHtml(run.startedAt || "")}</div></div>`;
      row.addEventListener("click", () => void selectWorkflowRun(run));
      els.workflowRunList.appendChild(row);
    });
  }

  function loadRevisionOntoCanvas(revision) {
    if (!revision?.workflowDefinition) return;
    hydrateWorkflow(revision.workflowDefinition);
    state.definitionVersion = revision.version;
    state.definitionStatus = revision.status;
    setWorkflowStatus(`${revision.status || "DRAFT"} v${revision.version} (loaded)`);
    saveCanvasPositions();
    renderAll();
    zoomToFit();
    toast(`已载入版本 v${revision.version} 到画布`);
  }

  async function rollbackDefinitionVersion(version) {
    if (!state.definitionId) return;
    if (!window.confirm(`回滚到版本 v${version}？将生成一个新的草稿版本。`)) return;
    await runCommand({
      request: () => requestJson(API.rollbackDefinition(state.definitionId, version), { method: "POST" }),
      onSuccess: async (response) => {
        state.definitionVersion = response.version;
        state.definitionStatus = response.status;
        hydrateWorkflow(response.workflowDefinition);
        setWorkflowStatus(`${response.status || "DRAFT"} v${response.version} (rollback)`);
        saveCanvasPositions();
        renderAll();
        zoomToFit();
        await loadDefinitions();
        els.definitionSelect.value = response.definitionId;
        await loadDefinitionHistory();
        toast(`已回滚到 v${version}（生成 v${response.version}）`);
      },
      onError: (error) => toast(error.message, true)
    });
  }

  async function selectWorkflowRun(run) {
    if (!run?.runId) return;
    state.lastRunId = run.runId;
    setWorkflowStatus(`Run ${run.runId.slice(0, 8)} · ${run.status}`);
    openRunDrawer();
    await refreshRunTrace(run.runId);
    toast(`已载入运行 ${run.runId.slice(0, 8)}`);
  }

  // ============================================================
  // 运行抽屉 + 友好输入
  // ============================================================
  function bindRunDrawer() {
    els.runDrawerToggle?.addEventListener("click", () => {
      const collapsed = els.runDrawer.classList.toggle("collapsed");
      els.runDrawerToggle.setAttribute("aria-expanded", String(!collapsed));
    });
    els.inputModeSeg?.querySelectorAll("[data-input-mode]").forEach((button) => {
      button.addEventListener("click", () => setInputMode(button.dataset.inputMode));
    });
    renderRunInputForm();
  }

  function openRunDrawer() {
    els.runDrawer?.classList.remove("collapsed");
    els.runDrawerToggle?.setAttribute("aria-expanded", "true");
  }

  function setInputMode(mode) {
    state.inputMode = mode;
    els.inputModeSeg.querySelectorAll("[data-input-mode]").forEach((b) => b.classList.toggle("active", b.dataset.inputMode === mode));
    if (mode === "form") {
      renderRunInputForm();
      els.runInputForm.classList.remove("hidden");
      els.workflowInput.classList.add("hidden");
    } else {
      syncFormToJson();
      els.runInputForm.classList.add("hidden");
      els.workflowInput.classList.remove("hidden");
    }
  }

  function currentInputObject() {
    try { const v = JSON.parse(els.workflowInput.value || "{}"); return v && typeof v === "object" && !Array.isArray(v) ? v : { value: v }; }
    catch (error) { return { message: "" }; }
  }

  function renderRunInputForm() {
    const obj = currentInputObject();
    const keys = Object.keys(obj);
    if (keys.length === 0) { obj.message = ""; keys.push("message"); }
    els.runInputForm.innerHTML = "";
    keys.forEach((key) => {
      const shell = fieldShell(key);
      const value = obj[key];
      let control;
      if (value && typeof value === "object") {
        control = document.createElement("textarea");
        control.className = "code-input";
        control.value = formatJson(value);
        control.dataset.json = "true";
      } else if (typeof value === "string" && (value.length > 40 || key === "message")) {
        control = document.createElement("textarea");
        control.className = "text-area";
        control.value = value;
      } else if (typeof value === "number") {
        control = document.createElement("input");
        control.className = "text-input"; control.type = "number"; control.value = value;
        control.dataset.num = "true";
      } else {
        control = document.createElement("input");
        control.className = "text-input"; control.type = "text"; control.value = value ?? "";
      }
      control.dataset.key = key;
      control.addEventListener("input", syncFormToJson);
      shell.appendChild(control);
      els.runInputForm.appendChild(shell);
    });
  }

  function syncFormToJson() {
    if (state.inputMode !== "form" || !els.runInputForm) return;
    const obj = {};
    els.runInputForm.querySelectorAll("[data-key]").forEach((control) => {
      const key = control.dataset.key;
      if (control.dataset.json === "true") {
        try { obj[key] = JSON.parse(control.value || "null"); } catch (error) { obj[key] = control.value; }
      } else if (control.dataset.num === "true") {
        obj[key] = control.value === "" ? null : Number(control.value);
      } else {
        obj[key] = control.value;
      }
    });
    els.workflowInput.value = formatJson(obj);
  }

  // ============================================================
  // 对话（合并：普通对话 / 工具智能体 / 知识库问答）
  // ============================================================
  function bindChat() {
    els.chatModeSeg?.querySelectorAll("[data-chat-mode]").forEach((button) => {
      button.addEventListener("click", () => {
        state.chatMode = button.dataset.chatMode;
        els.chatModeSeg.querySelectorAll("[data-chat-mode]").forEach((b) => b.classList.toggle("active", b === button));
        els.streamChat.classList.toggle("hidden", state.chatMode !== "chat");
        renderChat();
      });
    });
    els.sendChat?.addEventListener("click", () => void sendChatMessage(false));
    els.streamChat?.addEventListener("click", () => void sendChatMessage(true));
    els.chatMessage?.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey) { event.preventDefault(); void sendChatMessage(false); }
    });
    els.chatMessage?.addEventListener("input", () => {
      els.chatMessage.style.height = "auto";
      els.chatMessage.style.height = `${Math.min(els.chatMessage.scrollHeight, 160)}px`;
    });
  }

  const CHAT_HINTS = {
    chat: "与大模型直接对话。可点「流式」逐字输出。",
    agent: "大模型自动调用本地工具（计算、时间等）。MCP 远程工具需 DEMO_MCP_ENABLED=true。",
    rag: "基于知识库文档检索后作答，答案会附引用来源。"
  };

  function renderChat() {
    if (!els.chatTranscript) return;
    els.chatHint.textContent = CHAT_HINTS[state.chatMode] || "";
    const history = state.chatHistories[state.chatMode] || [];
    els.chatTranscript.innerHTML = "";
    if (history.length === 0) {
      const empty = document.createElement("div");
      empty.className = "chat-empty";
      empty.innerHTML = `<div class="chat-empty-ico">💬</div><div>开始一段${escapeHtml(modeName(state.chatMode))}</div>`;
      els.chatTranscript.appendChild(empty);
      return;
    }
    history.forEach((message) => els.chatTranscript.appendChild(buildBubble(message)));
    els.chatTranscript.scrollTop = els.chatTranscript.scrollHeight;
  }

  function modeName(mode) { return { chat: "普通对话", agent: "工具智能体对话", rag: "知识库问答" }[mode] || "对话"; }

  function buildBubble(message) {
    const row = document.createElement("div");
    row.className = `bubble-row ${message.role}`;
    const avatar = document.createElement("div");
    avatar.className = `bubble-avatar ${message.role}`;
    avatar.textContent = message.role === "user" ? "我" : "AI";
    const bubble = document.createElement("div");
    bubble.className = "bubble";
    if (message.error) bubble.classList.add("is-error");
    if (message.streaming) bubble.classList.add("streaming");
    bubble.textContent = message.text || "";
    if (message.extras && message.extras.length) {
      const extras = document.createElement("div");
      extras.className = "bubble-extras";
      message.extras.forEach((extra) => extras.appendChild(buildExtraChip(extra)));
      bubble.appendChild(extras);
    }
    row.append(avatar, bubble);
    return row;
  }

  function buildExtraChip(extra) {
    const chip = document.createElement("div");
    chip.className = `extra-chip ${extra.cls || ""}`;
    chip.innerHTML = `<div class="extra-title">${escapeHtml(extra.title)}</div>${extra.sub ? `<div class="extra-sub">${escapeHtml(extra.sub)}</div>` : ""}`;
    return chip;
  }

  async function sendChatMessage(stream) {
    const text = els.chatMessage.value.trim();
    if (!text) return;
    const history = state.chatHistories[state.chatMode];
    history.push({ role: "user", text });
    els.chatMessage.value = "";
    els.chatMessage.style.height = "auto";
    const assistant = { role: "ai", text: "", streaming: stream, extras: [] };
    history.push(assistant);
    renderChat();

    try {
      if (stream && state.chatMode === "chat") {
        await streamChatInto(assistant, text);
      } else {
        const response = await chatRequestForMode(text);
        assistant.text = response.answer || "（无回答）";
        assistant.extras = extrasForMode(response);
      }
    } catch (error) {
      assistant.error = true;
      assistant.text = `出错了：${error.message}`;
    } finally {
      assistant.streaming = false;
      els.chatModePill.textContent = "就绪";
      renderChat();
      if (state.chatMode !== "chat") void loadRuns();
    }
  }

  function chatRequestForMode(message) {
    if (state.chatMode === "agent") return requestJson(API.toolChat, { method: "POST", body: { conversationId: "workbench-agent", message } });
    if (state.chatMode === "rag") return requestJson(API.ragChat, { method: "POST", body: { conversationId: "workbench-rag", message } });
    return requestJson(API.chat, { method: "POST", body: { conversationId: "workbench", message } });
  }

  function extrasForMode(response) {
    if (state.chatMode === "agent" && Array.isArray(response.toolCalls)) {
      return response.toolCalls.map((call) => ({
        cls: call.succeeded ? "tool-ok" : "tool-err",
        title: `🔧 ${call.toolName || "tool"} · ${call.succeeded ? "成功" : "失败"}`,
        sub: `${call.provider || "local"}${call.remote ? " · 远程" : ""} · ${call.durationMs ?? 0}ms${call.errorMessage ? " · " + call.errorMessage : ""}`
      }));
    }
    if (state.chatMode === "rag" && Array.isArray(response.retrievedContext)) {
      return response.retrievedContext.map((ctx) => ({
        cls: "",
        title: `📎 ${ctx.title || "文档 " + ctx.documentId}`,
        sub: `相关度 ${typeof ctx.score === "number" ? ctx.score.toFixed(3) : ctx.score} · ${truncate(ctx.snippet || "", 80)}`
      }));
    }
    return [];
  }

  async function streamChatInto(assistant, message) {
    els.chatModePill.textContent = "流式中…";
    const parts = [];
    const response = await fetch(API.chatStream, {
      method: "POST",
      headers: authHeaders({ Accept: "text/event-stream", "Content-Type": "application/json" }),
      body: JSON.stringify({ conversationId: "workbench", message })
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    await consumeSse(response, (eventName, data) => {
      if (eventName === "message" && data?.delta) {
        parts.push(data.delta);
        assistant.text = parts.join("");
        const last = els.chatTranscript.querySelector(".bubble-row:last-child .bubble");
        if (last) last.textContent = assistant.text;
      }
      if (eventName === "done" && data?.answer) assistant.text = data.answer;
      if (eventName === "error") throw new Error(data?.error || data?.message || "流式失败");
    });
  }

  // ============================================================
  // 知识库
  // ============================================================
  function bindLibrary() {
    els.saveDocument?.addEventListener("click", () => void saveDocument());
    els.refreshDocuments?.addEventListener("click", () => void loadDocuments());
    els.askRag?.addEventListener("click", () => void askRag());
  }

  async function saveDocument() {
    await runCommand({
      request: () => requestJson(API.saveDocument, { method: "POST", body: { title: els.documentTitle.value, content: els.documentContent.value } }),
      successToast: "文档已保存",
      onSuccess: async () => { await Promise.allSettled([loadDocuments(), loadHealth()]); }
    });
  }

  async function loadDocuments() {
    try {
      const page = await requestJson(`${API.listDocuments}?page=0&size=20`);
      const documents = page?.content || [];
      els.documentList.innerHTML = "";
      if (documents.length === 0) { els.documentList.appendChild(emptyDiv("暂无文档，先在左侧保存一篇")); }
      documents.forEach((document_) => {
        const item = document.createElement("div");
        item.className = "doc-item";
        item.innerHTML = `<div><div class="doc-title">${escapeHtml(document_.title || "文档 " + document_.id)}</div>
          <div class="doc-meta">#${document_.id} · ${escapeHtml(statusCn(document_.indexStatus) || "未知")} · ${document_.contentLength || 0} 字 · ${escapeHtml(document_.createdAt || "")}</div></div>`;
        const del = document.createElement("button");
        del.className = "icon-btn-sm"; del.title = "删除"; del.setAttribute("aria-label", "删除");
        del.innerHTML = '<svg viewBox="0 0 24 24"><path d="M5 7h14M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>';
        del.addEventListener("click", () => void deleteDocument(document_.id));
        item.appendChild(del);
        els.documentList.appendChild(item);
      });
      if (state.health) updateRagHint(state.health);
    } catch (error) {
      els.documentList.innerHTML = "";
      els.documentList.appendChild(emptyDiv(`无法加载文档：${error.message}`));
    }
  }

  async function deleteDocument(id) {
    if (!window.confirm("删除该文档？")) return;
    try {
      await requestJson(API.deleteDocument(id), { method: "DELETE" });
      toast("文档已删除");
      await Promise.allSettled([loadDocuments(), loadHealth()]);
    } catch (error) { toast(error.message, true); }
  }

  async function askRag() {
    const message = els.ragMessage.value.trim();
    if (!message) return;
    els.ragOutput.classList.remove("hidden");
    els.ragOutput.textContent = "检索中…";
    try {
      const response = await requestJson(API.ragChat, { method: "POST", body: { conversationId: "workbench-rag", message } });
      els.ragOutput.innerHTML = "";
      const answer = document.createElement("div");
      answer.textContent = response.answer || "（无回答）";
      els.ragOutput.appendChild(answer);
      if (Array.isArray(response.retrievedContext) && response.retrievedContext.length) {
        const extras = document.createElement("div");
        extras.className = "bubble-extras";
        response.retrievedContext.forEach((ctx) => extras.appendChild(buildExtraChip({
          title: `📎 ${ctx.title || "文档 " + ctx.documentId}`,
          sub: `相关度 ${typeof ctx.score === "number" ? ctx.score.toFixed(3) : ctx.score} · ${truncate(ctx.snippet || "", 80)}`
        })));
        els.ragOutput.appendChild(extras);
      }
    } catch (error) {
      els.ragOutput.textContent = `出错了：${error.message}`;
    }
  }

  // ============================================================
  // 运行记录
  // ============================================================
  function bindRuns() {
    els.refreshRuns?.addEventListener("click", () => void loadRuns());
  }

  async function loadRuns() {
    try {
      const runsPage = await requestJson(`${API.runs}?page=0&size=20`);
      const runs = runsPage?.content || [];
      els.runList.innerHTML = "";
      if (runs.length === 0) { els.runList.appendChild(emptyDiv("暂无运行记录")); return; }
      runs.forEach((run) => {
        const card = document.createElement("div");
        card.className = "run-card";
        card.classList.toggle("active", state.activeRunCardId === run.runId);
        card.innerHTML = `<div class="run-card-main"><div class="run-type">${escapeHtml(run.type || "run")}</div>
          <div class="run-sub">${escapeHtml(short(run.runId))} · ${escapeHtml(run.startedAt || "")}</div></div>`;
        card.appendChild(makeBadge(statusCn(run.status), `badge-${statusClass(run.status) === "success" ? "ok" : statusClass(run.status) === "failed" ? "err" : "run"}`));
        card.addEventListener("click", () => { state.activeRunCardId = run.runId; loadRuns(); void openRunDetail(run); });
        els.runList.appendChild(card);
      });
    } catch (error) {
      els.runList.innerHTML = "";
      els.runList.appendChild(emptyDiv(`无法加载：${error.message}`));
    }
  }

  async function openRunDetail(run) {
    els.runDetail.innerHTML = `<div class="run-detail-head"><h3>运行详情</h3></div>
      <div class="run-detail-head"><span class="item-meta">${escapeHtml(run.runId)}</span></div>`;
    const stepsHost = document.createElement("div");
    stepsHost.className = "step-list";
    els.runDetail.appendChild(stepsHost);
    try {
      const [steps, graph] = await Promise.allSettled([
        requestJson(API.runSteps(run.runId)),
        requestJson(API.workflowRunGraph(run.runId))
      ]);
      renderTraceSteps(stepsHost, steps.status === "fulfilled" ? steps.value : [], graph.status === "fulfilled" ? graph.value : null);
    } catch (error) {
      stepsHost.appendChild(emptyDiv(error.message));
    }
  }

  // ============================================================
  // 工具
  // ============================================================
  function bindTools() {
    els.refreshTools?.addEventListener("click", () => void loadTools());
  }

  async function loadTools() {
    const [tools, servers] = await Promise.allSettled([requestJson(API.tools), requestJson(API.mcpServers)]);
    renderDataList(els.toolList, tools.status === "fulfilled" ? tools.value : [], (tool) => ({
      title: tool.name, meta: `${tool.provider || "local"} · 远程=${boolCn(Boolean(tool.remote))}`
    }));
    const serverItems = servers.status === "fulfilled" ? servers.value : [];
    if (serverItems.length === 0 && state.health && !state.health.mcpEnabled) {
      renderDataList(els.mcpServerList, [{ name: "MCP 未启用", hint: "设置 DEMO_MCP_ENABLED=true 并配置 spring.ai.mcp.client.* 以启用远程工具。" }],
        (server) => ({ title: server.name, meta: server.hint }));
      return;
    }
    renderDataList(els.mcpServerList, serverItems, (server) => ({
      title: server.name, meta: `${server.transport || "stdio"} · 启用=${boolCn(Boolean(server.enabled))}`
    }));
  }

  // ============================================================
  // 通用命令执行
  // ============================================================
  async function runCommand({ request, onSuccess, onError, outputEl, successToast, errorToast = true }) {
    try {
      const response = await request();
      if (outputEl) outputEl.textContent = formatJson(response);
      if (onSuccess) await onSuccess(response);
      if (successToast) toast(successToast);
      return response;
    } catch (error) {
      if (outputEl) outputEl.textContent = formatJson({ error: error.message });
      if (onError) onError(error);
      else if (errorToast) toast(error.message, true);
      return null;
    }
  }

  // ============================================================
  // HTTP / SSE
  // ============================================================
  async function requestJson(url, options = {}) {
    const init = { method: options.method || "GET", headers: authHeaders({ Accept: "application/json" }) };
    if (options.body !== undefined) { init.headers["Content-Type"] = "application/json"; init.body = JSON.stringify(options.body); }
    const response = await fetch(url, init);
    const text = await response.text();
    const payload = text ? JSON.parse(text) : null;
    if (!response.ok || payload?.success === false) throw new Error(payload?.message || payload?.code || `HTTP ${response.status}`);
    return payload?.data ?? payload;
  }

  async function consumeSse(response, onEvent) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      buffer = drainSseBuffer(buffer, onEvent);
    }
    drainSseBuffer(`${buffer}\n\n`, onEvent);
  }

  function drainSseBuffer(buffer, onEvent) {
    const blocks = buffer.split("\n\n");
    const remainder = blocks.pop() || "";
    blocks.forEach((block) => {
      if (!block.trim()) return;
      let eventName = "message";
      const dataLines = [];
      block.split("\n").forEach((line) => {
        if (line.startsWith("event:")) eventName = line.slice(6).trim();
        else if (line.startsWith("data:")) dataLines.push(line.slice(5).trim());
      });
      if (dataLines.length === 0) return;
      try { onEvent(eventName, JSON.parse(dataLines.join("\n"))); }
      catch (error) { onEvent(eventName, { message: dataLines.join("\n") }); }
    });
    return remainder;
  }

  // ============================================================
  // 运行步骤渲染（含复合容器分组）
  // ============================================================
  function renderTraceSteps(container, steps, graph) {
    container.innerHTML = "";
    if (!steps || steps.length === 0) { container.appendChild(emptyDiv("暂无步骤")); return; }
    const childStepIds = new Set();
    const containers = buildCompositeContainers(graph, steps);
    containers.forEach((composite) => {
      appendTraceContainer(container, composite);
      composite.childStepIds.forEach((stepId) => childStepIds.add(stepId));
    });
    steps.forEach((step) => {
      if (childStepIds.has(step.nodeName)) return;
      appendTraceStepItem(container, step, 0);
    });
  }

  function buildCompositeContainers(graph, steps) {
    if (graph && graph.nodes) {
      return graph.nodes.filter((node) => (node.children || []).length > 0).map((node) => {
        const children = (node.children || []).map((child) => {
          const stepName = `workflow_node_${child.id}`;
          return { ...child, step: steps.find((step) => step.nodeName === stepName) || null };
        });
        const childStepIds = children.map((child) => `workflow_node_${child.id}`);
        const iterationLabel = node.compositeRole === "LOOP" && node.iterations != null ? ` · x${node.iterations} 次` : "";
        return { title: `${node.id} (${node.type})${iterationLabel}`, children, childStepIds };
      });
    }
    return buildCompositeContainersFromSteps(steps);
  }

  function buildCompositeContainersFromSteps(steps) {
    if (!steps || steps.length === 0) return [];
    const groups = new Map();
    const claimStep = (groupKey, title, step, childId) => {
      if (!groups.has(groupKey)) groups.set(groupKey, { title, children: [], childStepIds: [] });
      const group = groups.get(groupKey);
      if (group.childStepIds.includes(step.nodeName)) return;
      group.children.push({ id: childId, status: step.status, stepId: step.stepId, type: title.includes("(") ? title.split("(")[1].replace(")", "") : "step", step });
      group.childStepIds.push(step.nodeName);
    };
    steps.forEach((step) => {
      const nodeId = traceStepNodeId(step.nodeName);
      if (!nodeId) return;
      const subgraphSep = nodeId.indexOf("::");
      const dynamicSep = nodeId.indexOf(":dynamic:");
      if (subgraphSep > 0) { claimStep(`subgraph:${nodeId.substring(0, subgraphSep)}`, `${nodeId.substring(0, subgraphSep)} (subgraph)`, step, nodeId); return; }
      if (dynamicSep > 0) { claimStep(`dynamic:${nodeId.substring(0, dynamicSep)}`, `${nodeId.substring(0, dynamicSep)} (dynamic)`, step, nodeId); }
    });
    state.nodes.filter((node) => node.type === "loop").forEach((loopNode) => {
      const loopStepName = `workflow_node_${loopNode.id}`;
      if (!steps.some((step) => step.nodeName === loopStepName)) return;
      const bodyStart = state.edges.find((edge) => edge.from === loopNode.id && edge.condition === "body");
      if (!bodyStart) return;
      collectLinearChain(bodyStart.to, new Set([loopNode.id])).forEach((bodyNodeId) => {
        const bodyStep = steps.find((step) => step.nodeName === `workflow_node_${bodyNodeId}`);
        if (bodyStep) claimStep(`loop:${loopNode.id}`, `${loopNode.id} (loop)`, bodyStep, bodyNodeId);
      });
      const loopBackId = findLoopBackId(loopNode.id);
      if (loopBackId) {
        const loopBackStep = steps.find((step) => step.nodeName === `workflow_node_${loopBackId}`);
        if (loopBackStep) claimStep(`loop:${loopNode.id}`, `${loopNode.id} (loop)`, loopBackStep, loopBackId);
      }
    });
    state.nodes.filter((node) => node.type === "parallel").forEach((parallelNode) => {
      const parallelStepName = `workflow_node_${parallelNode.id}`;
      const hasParallel = steps.some((step) => step.nodeName === parallelStepName);
      const branchEdges = state.edges.filter((edge) => edge.from === parallelNode.id && !edge.condition);
      let grouped = false;
      branchEdges.forEach((edge) => {
        const syntheticId = `workflow_branch_${parallelNode.id}_${edge.to}`;
        const syntheticStep = steps.find((step) => step.nodeName === `workflow_node_${syntheticId}`);
        if (syntheticStep) { claimStep(`parallel:${parallelNode.id}`, `${parallelNode.id} (parallel)`, syntheticStep, syntheticId); grouped = true; }
        collectLinearChain(edge.to, new Set([parallelNode.id])).forEach((branchNodeId) => {
          const branchStep = steps.find((step) => step.nodeName === `workflow_node_${branchNodeId}`);
          if (branchStep) { claimStep(`parallel:${parallelNode.id}`, `${parallelNode.id} (parallel)`, branchStep, branchNodeId); grouped = true; }
        });
      });
      if (!grouped && hasParallel) {
        steps.forEach((step) => {
          const nodeId = traceStepNodeId(step.nodeName);
          if (nodeId.startsWith("workflow_branch_") && nodeId.includes(`_${parallelNode.id}_`)) claimStep(`parallel:${parallelNode.id}`, `${parallelNode.id} (parallel)`, step, nodeId);
        });
      }
    });
    return Array.from(groups.values());
  }

  function collectLinearChain(startId, stopIds) {
    const ids = [];
    let current = startId;
    const visited = new Set();
    while (current && !visited.has(current) && !stopIds.has(current)) {
      visited.add(current);
      const node = state.nodes.find((item) => item.id === current);
      if (!node) break;
      if (node.type === "loop_back" || node.type === "join") break;
      ids.push(current);
      const nextEdge = state.edges.find((edge) => edge.from === current);
      if (!nextEdge) break;
      current = nextEdge.to;
    }
    return ids;
  }

  function findLoopBackId(loopId) {
    const bodyEdge = state.edges.find((edge) => edge.from === loopId && edge.condition === "body");
    if (!bodyEdge) return null;
    let current = bodyEdge.to;
    const visited = new Set();
    while (current && !visited.has(current)) {
      visited.add(current);
      const node = state.nodes.find((item) => item.id === current);
      if (node?.type === "loop_back") return node.id;
      const nextEdge = state.edges.find((edge) => edge.from === current);
      current = nextEdge?.to;
    }
    return null;
  }

  function traceStepNodeId(nodeName) {
    if (!nodeName) return "";
    return nodeName.startsWith("workflow_node_") ? nodeName.slice("workflow_node_".length) : nodeName;
  }

  function appendTraceContainer(container, composite) {
    const group = document.createElement("div");
    group.className = "step-item step-group";
    const title = document.createElement("span");
    title.className = "step-group-title";
    title.textContent = composite.title;
    group.appendChild(title);
    composite.children.forEach((child) => {
      if (child.step) { appendTraceStepItem(group, child.step, 1); return; }
      const item = document.createElement("div");
      item.className = "step-item step-child";
      item.innerHTML = `<div class="step-row"><span class="step-dot ${statusClass(child.status)}">${statusGlyph(child.status)}</span>
        <span class="step-name">${escapeHtml(child.id)}</span>
        <span class="step-status ${statusClass(child.status)}">${escapeHtml(statusCn(child.status) || "未执行")}</span></div>`;
      group.appendChild(item);
    });
    container.appendChild(group);
  }

  function appendTraceStepItem(container, step, indentLevel) {
    const item = document.createElement("div");
    item.className = indentLevel > 0 ? "step-item step-child" : "step-item";
    const cls = statusClass(step.status);
    const row = document.createElement("div");
    row.className = "step-row";
    row.innerHTML = `<span class="step-dot ${cls}">${statusGlyph(step.status)}</span>
      <span class="step-name">${escapeHtml(friendlyStepName(step.nodeName))}</span>
      <span class="step-status ${cls}">${escapeHtml(statusCn(step.status))}</span>`;
    item.appendChild(row);
    const meta = document.createElement("div");
    meta.className = "step-meta";
    meta.textContent = step.stepId || "";
    item.appendChild(meta);

    const output = parseStepOutput(step);
    const usage = findTokenUsage(output);
    const summary = tokenUsageSummary(usage);
    if (summary) {
      const usageMeta = document.createElement("div");
      usageMeta.className = "token-usage";
      usageMeta.textContent = summary;
      item.appendChild(usageMeta);
    }
    if (output !== null && output !== undefined && output !== "") {
      const details = document.createElement("details");
      details.className = "trace-details";
      const detailTitle = document.createElement("summary");
      detailTitle.textContent = "查看详情";
      const pre = document.createElement("pre");
      pre.className = "trace-json";
      pre.textContent = typeof output === "string" ? output : formatJson(output);
      details.append(detailTitle, pre);
      item.appendChild(details);
    }
    container.appendChild(item);
  }

  function friendlyStepName(nodeName) {
    const id = traceStepNodeId(nodeName);
    const node = state.nodes.find((n) => n.id === id);
    return node ? `${nodeLabel(node.type)} · ${id}` : (nodeName || "");
  }

  function statusGlyph(status) {
    const cls = statusClass(status);
    return cls === "success" ? "✓" : cls === "failed" ? "✕" : cls === "running" ? "•" : "·";
  }

  function parseStepOutput(step) {
    if (!step || !step.outputJson) return null;
    try { return JSON.parse(step.outputJson); } catch (error) { return step.outputJson; }
  }

  function findTokenUsage(output) {
    if (!output || typeof output !== "object") return null;
    if (output.tokenUsage) return output.tokenUsage;
    if (output.output && typeof output.output === "object" && output.output.tokenUsage) return output.output.tokenUsage;
    return null;
  }

  function tokenUsageSummary(usage) {
    if (!usage) return "";
    const model = usage.model || "未知模型";
    const prompt = usage.promptTokens ?? "-";
    const completion = usage.completionTokens ?? "-";
    const total = usage.totalTokens ?? "-";
    return `Tokens · ${model} · 输入 ${prompt} · 输出 ${completion} · 合计 ${total}`;
  }

  // ============================================================
  // 通用渲染
  // ============================================================
  function renderDataList(container, items, mapper) {
    if (!container) return;
    container.innerHTML = "";
    if (!items || items.length === 0) { container.appendChild(emptyDiv("暂无数据")); return; }
    items.forEach((item) => {
      const view = mapper(item);
      const element = document.createElement("div");
      element.className = "data-item";
      element.innerHTML = `<strong>${escapeHtml(view.title)}</strong><div class="item-meta">${escapeHtml(view.meta || "")}</div>`;
      container.appendChild(element);
    });
  }

  function emptyDiv(text) {
    const div = document.createElement("div");
    div.className = "empty-state";
    div.textContent = text;
    return div;
  }

  function makeBadge(text, cls) {
    const span = document.createElement("span");
    span.className = `badge ${cls || ""}`;
    span.textContent = text;
    return span;
  }

  // ============================================================
  // 工作流定义构建
  // ============================================================
  function buildWorkflowDefinition() {
    return {
      nodes: state.nodes.map((node) => ({ id: node.id, type: node.type, config: cleanConfig(node.config) })),
      edges: state.edges.map((edge) => {
        const payload = { from: edge.from, to: edge.to };
        if (edge.condition) payload.condition = edge.condition;
        return payload;
      })
    };
  }

  function cleanConfig(config) {
    const cleaned = {};
    Object.entries(config || {}).forEach(([key, value]) => {
      if (value === "" || value === null || value === undefined) return;
      cleaned[key] = value;
    });
    return cleaned;
  }

  // ============================================================
  // 表单控件
  // ============================================================
  function findSelectedNode() { return state.nodes.find((node) => node.id === state.selectedNodeId) || null; }

  function schemaForType(type) {
    return state.schemas.find((s) => s.type === type) || fallbackSchemas.find((s) => s.type === type) || { type, displayName: type, configFields: [] };
  }

  function defaultConfig(schema) {
    const config = {};
    (schema.configFields || []).forEach((field) => {
      if (field.defaultValue !== null && field.defaultValue !== undefined && field.defaultValue !== "") config[field.name] = cloneValue(field.defaultValue);
    });
    return config;
  }

  function cloneValue(value) {
    if (Array.isArray(value) || (value && typeof value === "object")) return JSON.parse(JSON.stringify(value));
    return value;
  }

  const FIELD_LABELS = {
    topK: "检索条数 topK", prompt: "提示词", model: "模型", toolName: "工具名",
    left: "左值", operator: "运算符", right: "右值", maxIterations: "最大迭代",
    itemsFrom: "数据来源", action: "动作", definitionId: "子图定义", version: "版本"
  };
  function configFieldLabel(name) { return FIELD_LABELS[name] || name; }

  function nodeSummary(node) {
    if (node.type === "llm") return String(node.config.prompt || "提示词").slice(0, 54);
    if (node.type === "tool") return String(node.config.toolName || "getCurrentTime");
    if (node.type === "retriever") return `topK ${node.config.topK || 3}`;
    if (node.type === "condition") return `${node.config.operator || "contains"}`;
    if (node.type === "subgraph") return node.config.definitionId ? `定义 ${node.config.definitionId}` : "子图";
    if (node.type === "loop") return `最多 ${node.config.maxIterations || 10} 次`;
    if (node.type === "dynamic") return String(node.config.itemsFrom || "items").slice(0, 40);
    return nodeLabel(node.type);
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
    state.nodes.forEach((node) => appendOption(select, node.id, node.id));
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
  function short(value) { return value ? String(value).slice(0, 8) : ""; }
  function truncate(value, n) { const s = String(value || ""); return s.length > n ? s.slice(0, n) + "…" : s; }

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
})();
