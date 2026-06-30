(() => {
  "use strict";

  const API = {
    health: "/api/health",
    devToken: "/api/auth/dev-token",
    nodeSchemas: "/api/workflows/node-schemas",
    validateWorkflow: "/api/workflows/validate",
    previewGraph: "/api/workflows/preview-graph",
    runWorkflow: "/api/workflows/run",
    definitions: "/api/workflows/definitions",
    definitionRevisions: (definitionId) => `/api/workflows/definitions/${encodeURIComponent(definitionId)}/revisions`,
    rollbackDefinition: (definitionId, version) => `/api/workflows/definitions/${encodeURIComponent(definitionId)}/rollback/${version}`,
    workflowRuns: (definitionId) => `/api/workflows/runs?definitionId=${encodeURIComponent(definitionId)}&page=0&size=20`,
    publishDefinition: (definitionId) => `/api/workflows/definitions/${encodeURIComponent(definitionId)}/publish`,
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

  const nodePaletteColors = {
    start: "var(--node-start)",
    retriever: "var(--node-rag)",
    llm: "var(--node-llm)",
    tool: "var(--node-tool)",
    condition: "var(--node-flow)",
    parallel: "var(--node-flow)",
    join: "var(--node-flow)",
    loop: "var(--node-flow)",
    loop_back: "var(--node-flow)",
    subgraph: "var(--node-flow)",
    dynamic: "var(--node-flow)",
    end: "var(--node-start)"
  };

  const CANVAS_POSITIONS_KEY_PREFIX = "workflow-canvas-positions:";

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
    toastTimer: null
  };

  const els = {};
  // Bearer token used for every API/SSE call. Populated by bootstrapAuth() in local/demo mode.
  let authToken = null;
  const viewRoutes = {
    tools: () => void loadTools(),
    runs: () => void loadRuns(),
    rag: () => void loadDocuments(),
    agent: () => void loadHealth()
  };

  class WorkflowCanvasController {
    init() {
      cacheElements();
      bindNavigation();
      bindWorkflowActions();
      bindAssistantPanels();
      resetWorkflow();
      renderAll();
      void loadInitialData();
    }

    exportDefinition() {
      return buildWorkflowDefinition();
    }

    getState() {
      return {
        nodes: state.nodes,
        edges: state.edges,
        selectedNodeId: state.selectedNodeId,
        definitionId: state.definitionId
      };
    }
  }

  window.WorkflowCanvasController = new WorkflowCanvasController();

  document.addEventListener("DOMContentLoaded", () => {
    window.WorkflowCanvasController.init();
  });

  function cacheElements() {
    const ids = [
      "runtime-status", "workflow-status", "definition-name", "definition-select", "new-workflow",
      "load-definition", "save-definition", "publish-definition", "insert-loop-template",
      "validate-workflow", "run-workflow", "workflow-canvas",
      "edge-layer", "node-layer", "node-palette", "node-inspector", "inspector-empty", "inspector-form",
      "definition-history", "refresh-definition-history", "revision-list", "workflow-run-list",
      "delete-node", "add-edge", "edge-list", "workflow-input", "run-output", "trace-steps",
      "refresh-run-graph", "send-chat", "stream-chat", "chat-mode-pill", "chat-message", "chat-output",
      "send-tool-chat", "tool-chat-message", "tool-chat-output",
      "save-document", "refresh-documents", "document-list", "rag-hint",
      "ask-rag", "document-title", "document-content", "rag-message", "rag-output",
      "refresh-tools", "tool-list", "mcp-server-list", "refresh-runs", "run-list",
      "runtime-details", "toast"
    ];
    ids.forEach((id) => {
      els[toCamel(id)] = requiredElement(id);
    });
  }

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

  function bindWorkflowActions() {
    els.newWorkflow.addEventListener("click", () => {
      resetWorkflow();
      renderAll();
      void loadDefinitionHistory();
      toast("已新建工作流");
    });
    els.loadDefinition.addEventListener("click", () => {
      const selected = els.definitionSelect.value;
      if (selected) {
        void loadDefinition(selected);
      }
    });
    els.saveDefinition.addEventListener("click", () => void saveDefinition());
    els.publishDefinition.addEventListener("click", () => void publishDefinition());
    els.insertLoopTemplate.addEventListener("click", () => insertLoopTemplate());
    els.validateWorkflow.addEventListener("click", () => void validateWorkflow());
    els.runWorkflow.addEventListener("click", () => void runWorkflow());
    els.refreshRunGraph.addEventListener("click", () => {
      if (state.lastRunId) {
        void refreshRunTrace(state.lastRunId);
      }
    });
    els.deleteNode.addEventListener("click", deleteSelectedNode);
    els.refreshDefinitionHistory.addEventListener("click", () => void loadDefinitionHistory());
    els.addEdge.addEventListener("click", () => {
      if (state.nodes.length >= 2) {
        state.edges.push({ from: state.nodes[0].id, to: state.nodes[1].id, condition: "" });
        renderEdges();
        renderEdgeEditor();
      }
    });
    window.addEventListener("resize", renderEdges);
  }

  function bindAssistantPanels() {
    els.sendChat.addEventListener("click", () => void sendChat());
    els.streamChat.addEventListener("click", () => void streamChat());
    els.sendToolChat.addEventListener("click", () => void sendToolChat());
    els.saveDocument.addEventListener("click", () => void saveDocument());
    els.refreshDocuments.addEventListener("click", () => void loadDocuments());
    els.askRag.addEventListener("click", () => void askRag());
    els.refreshTools.addEventListener("click", () => void loadTools());
    els.refreshRuns.addEventListener("click", () => void loadRuns());
  }

  async function loadInitialData() {
    await bootstrapAuth();
    await Promise.allSettled([
      loadHealth(),
      loadSchemas(),
      loadDefinitions(),
      loadTools(),
      loadRuns()
    ]);
  }

  // Fetches a short-lived dev token (local/demo mode) so the secured API accepts workbench calls.
  // In production the /api/auth/dev-token endpoint is disabled; the UI must be fronted by a real IdP.
  async function bootstrapAuth() {
    try {
      const data = await requestJson(API.devToken);
      authToken = data?.token ?? null;
      if (!authToken) {
        toast("未返回开发令牌，API 调用可能未授权", true);
      }
    } catch (error) {
      authToken = null;
      toast(`认证不可用：${error.message}。请通过你的 IdP 登录后再使用工作台。`, true);
    }
  }

  function authHeaders(extra = {}) {
    const headers = { ...extra };
    if (authToken) {
      headers.Authorization = `Bearer ${authToken}`;
    }
    return headers;
  }

  async function loadHealth() {
    try {
      const data = await requestJson(API.health);
      state.health = data;
      const modelLabel = data.modelConfigured ? data.model : "未配置";
      els.runtimeStatus.textContent = `${data.status} · ${modelLabel}`;
      els.runtimeDetails.textContent = [
        `workflow=${data.workflowRuntime} · publishRequired=${data.workflowRequirePublishedForRun}`,
        `strict=${data.strictMode} · fallback=${data.fallbackEnabled} · keywordFallback=${data.keywordFallbackEnabled}`,
        `embedding=${data.embeddingConfigured} · vector=${data.vectorStoreConfigured} · retriever=${data.ragRetriever}`,
        `indexedDocs=${data.indexedDocumentCount} · mcp=${data.mcpEnabled}`
      ].join("\n");
      updateRagHint(data);
    } catch (error) {
      els.runtimeStatus.textContent = "不可用";
      els.runtimeDetails.textContent = error.message;
    }
  }

  function updateRagHint(health) {
    if (!els.ragHint) {
      return;
    }
    if (health.vectorStoreConfigured && health.indexedDocumentCount === 0) {
      els.ragHint.textContent = "DashVector 已就绪，但当前 PostgreSQL 中没有已索引文档。请重新点击“保存文档”，否则 RAG 检索会返回空上下文。";
      els.ragHint.classList.remove("hidden");
      return;
    }
    if (!health.strictMode && !health.modelConfigured) {
      els.ragHint.textContent = "模型未配置，当前处于 demo fallback 模式。配置 DashScope 并开启 strict 可走真实阿里栈。";
      els.ragHint.classList.remove("hidden");
      return;
    }
    els.ragHint.classList.add("hidden");
  }

  async function loadSchemas() {
    try {
      const schemas = await requestJson(API.nodeSchemas);
      if (Array.isArray(schemas) && schemas.length > 0) {
        state.schemas = schemas;
      }
    } catch (error) {
      toast(error.message, true);
    }
    renderPalette();
  }

  async function loadDefinitions() {
    try {
      const definitions = await requestJson(API.definitions);
      state.savedDefinitions = Array.isArray(definitions) ? definitions : [];
      els.definitionSelect.innerHTML = "";
      appendOption(els.definitionSelect, "", "已保存的定义");
      state.savedDefinitions.forEach((definition) => {
        appendOption(els.definitionSelect, definition.definitionId, `${definition.name} v${definition.version}`);
      });
    } catch (error) {
      state.savedDefinitions = [];
      appendOption(els.definitionSelect, "", "No saved definitions");
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
      setWorkflowStatus(`${definition.status || "DRAFT"} v${definition.version}`);
      await loadDefinitionHistory();
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
    state.selectedNodeId = "start";
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
      ["start", { x: 70, y: 90 }],
      ["retriever_1", { x: 330, y: 90 }],
      ["llm_1", { x: 70, y: 250 }],
      ["end", { x: 330, y: 250 }]
    ]);
    loadCanvasPositions();
    els.definitionName.value = "智能体工作流";
    els.runOutput.textContent = "{}";
    els.traceSteps.innerHTML = "";
    setWorkflowStatus("Draft");
    renderDefinitionHistory([], []);
  }

  function hydrateWorkflow(definition) {
    state.nodes = (definition.nodes || []).map((node) => ({
      id: node.id,
      type: node.type,
      config: { ...(node.config || {}) }
    }));
    state.edges = (definition.edges || []).map((edge) => ({
      from: edge.from,
      to: edge.to,
      condition: edge.condition || ""
    }));
    state.positions = new Map();
    state.nodes.forEach((node, index) => {
      const row = Math.floor(index / 4);
      const col = index % 4;
      state.positions.set(node.id, { x: 70 + col * 260, y: 80 + row * 140 });
    });
    loadCanvasPositions();
    state.selectedNodeId = state.nodes[0]?.id || null;
  }

  function canvasPositionsStorageKey() {
    return `${CANVAS_POSITIONS_KEY_PREFIX}${state.definitionId || "draft"}`;
  }

  function saveCanvasPositions() {
    const payload = {};
    state.positions.forEach((position, nodeId) => {
      payload[nodeId] = position;
    });
    try {
      window.localStorage.setItem(canvasPositionsStorageKey(), JSON.stringify(payload));
    } catch (error) {
      // Ignore quota or privacy mode failures.
    }
  }

  function loadCanvasPositions() {
    try {
      const raw = window.localStorage.getItem(canvasPositionsStorageKey());
      if (!raw) {
        return;
      }
      const saved = JSON.parse(raw);
      Object.entries(saved || {}).forEach(([nodeId, position]) => {
        if (state.nodes.some((node) => node.id === nodeId) && position && typeof position.x === "number") {
          state.positions.set(nodeId, { x: position.x, y: position.y });
        }
      });
    } catch (error) {
      // Ignore malformed local storage entries.
    }
  }

  function renderAll() {
    renderPalette();
    renderNodes();
    renderEdges();
    renderInspector();
    renderEdgeEditor();
  }

  const NODE_LABELS = {
    start: "开始", retriever: "检索", llm: "大模型", tool: "工具",
    condition: "条件", parallel: "并行", join: "汇合", loop: "循环",
    loop_back: "循环回边", subgraph: "子图", dynamic: "动态", end: "结束"
  };

  function nodeLabel(typeOrSchema) {
    const type = typeof typeOrSchema === "string" ? typeOrSchema : typeOrSchema.type;
    const fallback = typeof typeOrSchema === "object" && typeOrSchema
      ? (typeOrSchema.displayName || type)
      : type;
    return NODE_LABELS[type] || fallback;
  }

  function renderPalette() {
    els.nodePalette.innerHTML = "";
    state.schemas.forEach((schema) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "palette-button";
      button.style.borderLeftColor = colorForType(schema.type);
      button.innerHTML = `<span>${escapeHtml(nodeLabel(schema))}</span><strong>+</strong>`;
      button.addEventListener("click", () => addNode(schema.type));
      els.nodePalette.appendChild(button);
    });
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
      element.style.borderLeftColor = colorForType(node.type);
      element.classList.toggle("selected", state.selectedNodeId === node.id);
      element.classList.toggle("connecting", state.connectSourceId === node.id);
      element.innerHTML = `
        <div class="node-header">
          <span class="node-id">${escapeHtml(node.id)}</span>
          <button class="node-port" type="button" aria-label="连接 ${escapeHtml(node.id)}">+</button>
        </div>
        <div class="node-body">
          <div class="node-type">${escapeHtml(nodeLabel(node.type))}</div>
          <div>${escapeHtml(nodeSummary(node))}</div>
        </div>
      `;
      element.addEventListener("pointerdown", (event) => startDrag(event, node.id));
      element.addEventListener("click", () => selectOrConnectNode(node.id));
      element.querySelector(".node-port").addEventListener("click", (event) => {
        event.stopPropagation();
        state.connectSourceId = state.connectSourceId === node.id ? null : node.id;
        renderNodes();
        toast(state.connectSourceId ? `从 ${node.id} 开始连线` : "已取消连线");
      });
      els.nodeLayer.appendChild(element);
    });
  }

  function renderEdges() {
    els.edgeLayer.innerHTML = "";
    const canvasRect = els.workflowCanvas.getBoundingClientRect();
    state.edges.forEach((edge) => {
      const from = getNodeCenter(edge.from, canvasRect, "right");
      const to = getNodeCenter(edge.to, canvasRect, "left");
      if (!from || !to) {
        return;
      }
      const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
      const mid = Math.max(40, Math.abs(to.x - from.x) / 2);
      path.setAttribute("d", `M ${from.x} ${from.y} C ${from.x + mid} ${from.y}, ${to.x - mid} ${to.y}, ${to.x} ${to.y}`);
      path.setAttribute("class", edge.condition ? "edge-path pending" : "edge-path");
      els.edgeLayer.appendChild(path);
      if (edge.condition) {
        const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
        label.setAttribute("x", String((from.x + to.x) / 2));
        label.setAttribute("y", String((from.y + to.y) / 2 - 8));
        label.setAttribute("fill", "#4c657f");
        label.setAttribute("font-size", "12");
        label.setAttribute("text-anchor", "middle");
        label.textContent = edge.condition;
        els.edgeLayer.appendChild(label);
      }
    });
  }

  function renderInspector() {
    const node = findSelectedNode();
    els.inspectorEmpty.classList.toggle("hidden", Boolean(node));
    els.inspectorForm.classList.toggle("hidden", !node);
    els.deleteNode.disabled = !node || node.type === "start" || node.type === "end";
    els.inspectorForm.innerHTML = "";
    if (!node) {
      return;
    }
    const idField = fieldShell("节点 ID");
    const idInput = textControl(node.id);
    idInput.addEventListener("change", () => renameNode(node.id, idInput.value.trim()));
    idField.appendChild(idInput);
    els.inspectorForm.appendChild(idField);

    const schema = schemaForType(node.type);
    const typeField = fieldShell("类型");
    const typeValue = document.createElement("input");
    typeValue.className = "text-input";
    typeValue.value = schema.displayName || node.type;
    typeValue.disabled = true;
    typeField.appendChild(typeValue);
    els.inspectorForm.appendChild(typeField);

    (schema.configFields || []).forEach((field) => {
      const shell = fieldShell(field.name);
      let control;
      if (node.type === "subgraph" && field.name === "definitionId") {
        control = subgraphDefinitionControl(node.config.definitionId || "");
        control.addEventListener("change", () => {
          node.config.definitionId = control.value;
          node.config.version = null;
          renderInspector();
          renderNodes();
        });
      } else if (node.type === "subgraph" && field.name === "version") {
        control = document.createElement("select");
        control.className = "text-input";
        control.dataset.subgraphVersion = "true";
        appendOption(control, "", "Latest");
        if (node.config.version != null && node.config.version !== "") {
          appendOption(control, String(node.config.version), `v${node.config.version}`);
          control.value = String(node.config.version);
        }
        void populateSubgraphVersionOptions(control, node.config.definitionId, node.config.version);
        control.addEventListener("change", () => {
          node.config.version = control.value ? Number.parseInt(control.value, 10) : null;
          renderNodes();
        });
      } else {
        control = controlForField(field, node.config[field.name]);
        control.addEventListener("change", () => {
          node.config[field.name] = parseControlValue(control.value, field.type);
          renderNodes();
        });
      }
      shell.appendChild(control);
      els.inspectorForm.appendChild(shell);
    });
  }

  function subgraphDefinitionControl(selectedId) {
    const select = document.createElement("select");
    select.className = "text-input";
    appendOption(select, "", "Select definition");
    state.savedDefinitions.forEach((definition) => {
      appendOption(select, definition.definitionId, `${definition.name} (${definition.definitionId})`);
    });
    select.value = selectedId || "";
    return select;
  }

  async function populateSubgraphVersionOptions(select, definitionId, selectedVersion) {
    if (!definitionId) {
      return;
    }
    try {
      const revisions = await requestJson(API.definitionRevisions(definitionId));
      select.innerHTML = "";
      appendOption(select, "", "Latest");
      revisions.forEach((revision) => {
        appendOption(select, String(revision.version), `v${revision.version} (${revision.status})`);
      });
      if (selectedVersion != null && selectedVersion !== "") {
        select.value = String(selectedVersion);
      }
    } catch (error) {
      toast(error.message, true);
    }
  }

  function renderEdgeEditor() {
    els.edgeList.innerHTML = "";
    if (state.edges.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "暂无连线";
      els.edgeList.appendChild(empty);
      return;
    }
    state.edges.forEach((edge, index) => {
      const row = document.createElement("div");
      row.className = "edge-row";
      const from = selectNodeControl(edge.from);
      const to = selectNodeControl(edge.to);
      const fromNode = state.nodes.find((node) => node.id === edge.from);
      const condition = edgeConditionControl(fromNode, edge.condition || "");
      condition.classList.add("edge-condition");
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "danger-button";
      remove.textContent = "删除连线";
      from.addEventListener("change", () => {
        edge.from = from.value;
        renderEdgeEditor();
        renderEdges();
      });
      to.addEventListener("change", () => {
        edge.to = to.value;
        renderEdgeEditor();
        renderEdges();
      });
      condition.addEventListener("change", () => {
        edge.condition = condition.value.trim();
        renderEdges();
      });
      remove.addEventListener("click", () => {
        state.edges.splice(index, 1);
        renderEdges();
        renderEdgeEditor();
      });
      row.append(from, to, condition, remove);
      els.edgeList.appendChild(row);
    });
  }

  function edgeConditionControl(fromNode, value) {
    if (fromNode?.type === "loop") {
      const select = document.createElement("select");
      select.className = "text-input";
      appendOption(select, "body", "body");
      appendOption(select, "exit", "exit");
      appendOption(select, "", "(none)");
      select.value = value || "";
      return select;
    }
    if (fromNode?.type === "condition") {
      const select = document.createElement("select");
      select.className = "text-input";
      appendOption(select, "true", "true");
      appendOption(select, "false", "false");
      appendOption(select, "", "(none)");
      select.value = value || "";
      return select;
    }
    return textControl(value);
  }

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
    const startNode = state.nodes.find((node) => node.type === "start");
    const endNode = state.nodes.find((node) => node.type === "end");
    if (startNode && !state.edges.some((edge) => edge.from === startNode.id && edge.to === loopId)) {
      state.edges.push({ from: startNode.id, to: loopId, condition: "" });
    }
    state.edges.push(
      { from: loopId, to: bodyId, condition: "body" },
      { from: bodyId, to: loopBackId, condition: "" },
      { from: loopBackId, to: loopId, condition: "" },
      { from: loopId, to: afterId, condition: "exit" },
      { from: afterId, to: endNode.id, condition: "" }
    );
    const baseY = 80 + Math.floor(state.nodes.length / 4) * 120;
    state.positions.set(loopId, { x: 200, y: baseY });
    state.positions.set(bodyId, { x: 420, y: baseY });
    state.positions.set(loopBackId, { x: 640, y: baseY });
    state.positions.set(afterId, { x: 420, y: baseY + 140 });
    loadCanvasPositions();
    state.selectedNodeId = loopId;
    saveCanvasPositions();
    renderAll();
    toast("已插入循环模板 — 请点“校验”检查拓扑");
  }

  function ensureStartEndNodes() {
    if (!state.nodes.some((node) => node.type === "start")) {
      state.nodes.unshift({ id: uniqueNodeId("start"), type: "start", config: {} });
    }
    if (!state.nodes.some((node) => node.type === "end")) {
      state.nodes.push({ id: uniqueNodeId("end"), type: "end", config: {} });
    }
  }

  function uniqueNodeId(base) {
    let index = 1;
    let id = `${base}_${index}`;
    while (state.nodes.some((node) => node.id === id)) {
      index += 1;
      id = `${base}_${index}`;
    }
    return id;
  }

  function addNode(type) {
    const baseId = type.replace(/[^a-zA-Z0-9_]/g, "_") || "node";
    let index = 1;
    let id = `${baseId}_${index}`;
    while (state.nodes.some((node) => node.id === id)) {
      index += 1;
      id = `${baseId}_${index}`;
    }
    const schema = schemaForType(type);
    const node = { id, type, config: defaultConfig(schema) };
    state.nodes.push(node);
    state.positions.set(id, { x: 120 + (state.nodes.length % 4) * 210, y: 180 + Math.floor(state.nodes.length / 4) * 120 });
    state.selectedNodeId = id;
    saveCanvasPositions();
    renderAll();
  }

  function selectOrConnectNode(nodeId) {
    if (state.connectSourceId && state.connectSourceId !== nodeId) {
      const duplicate = state.edges.some((edge) => edge.from === state.connectSourceId && edge.to === nodeId);
      if (!duplicate) {
        state.edges.push({ from: state.connectSourceId, to: nodeId, condition: "" });
      }
      state.connectSourceId = null;
    }
    state.selectedNodeId = nodeId;
    renderAll();
  }

  function startDrag(event, nodeId) {
    if (event.target.closest("button")) {
      return;
    }
    event.preventDefault();
    const start = state.positions.get(nodeId) || { x: 0, y: 0 };
    const origin = { x: event.clientX, y: event.clientY };
    const target = event.currentTarget;
    target.setPointerCapture(event.pointerId);
    const move = (moveEvent) => {
      const maxX = Math.max(10, els.workflowCanvas.clientWidth - target.offsetWidth - 10);
      const maxY = Math.max(10, els.workflowCanvas.clientHeight - target.offsetHeight - 10);
      const next = {
        x: clamp(start.x + moveEvent.clientX - origin.x, 10, maxX),
        y: clamp(start.y + moveEvent.clientY - origin.y, 10, maxY)
      };
      state.positions.set(nodeId, next);
      target.style.left = `${next.x}px`;
      target.style.top = `${next.y}px`;
      renderEdges();
    };
    const stop = () => {
      target.removeEventListener("pointermove", move);
      target.removeEventListener("pointerup", stop);
      target.removeEventListener("pointercancel", stop);
      saveCanvasPositions();
    };
    target.addEventListener("pointermove", move);
    target.addEventListener("pointerup", stop);
    target.addEventListener("pointercancel", stop);
  }

  function deleteSelectedNode() {
    const node = findSelectedNode();
    if (!node || node.type === "start" || node.type === "end") {
      return;
    }
    state.nodes = state.nodes.filter((item) => item.id !== node.id);
    state.edges = state.edges.filter((edge) => edge.from !== node.id && edge.to !== node.id);
    state.positions.delete(node.id);
    state.selectedNodeId = state.nodes[0]?.id || null;
    saveCanvasPositions();
    renderAll();
  }

  function renameNode(oldId, newId) {
    if (!newId || oldId === newId || state.nodes.some((node) => node.id === newId)) {
      renderInspector();
      return;
    }
    const node = state.nodes.find((item) => item.id === oldId);
    if (!node) {
      return;
    }
    node.id = newId;
    const position = state.positions.get(oldId);
    state.positions.delete(oldId);
    state.positions.set(newId, position || { x: 80, y: 80 });
    state.edges.forEach((edge) => {
      if (edge.from === oldId) {
        edge.from = newId;
      }
      if (edge.to === oldId) {
        edge.to = newId;
      }
    });
    state.selectedNodeId = newId;
    saveCanvasPositions();
    renderAll();
  }

  async function validateWorkflow() {
    await runCommand({
      request: () => requestJson(API.validateWorkflow, {
        method: "POST",
        body: { workflowDefinition: buildWorkflowDefinition() }
      }),
      outputEl: els.runOutput,
      onSuccess: async (response) => {
        setWorkflowStatus(response.valid ? "Valid" : "Invalid");
        toast(response.valid ? "工作流校验通过" : "工作流校验未通过", !response.valid);
        if (response.valid) {
          await previewWorkflow();
        }
      },
      onError: (error) => {
        setWorkflowStatus("Invalid");
        toast(error.message, true);
      }
    });
  }

  async function previewWorkflow() {
    const response = await requestJson(API.previewGraph, {
      method: "POST",
      body: { workflowDefinition: buildWorkflowDefinition() }
    });
    if (response.mermaid) {
      els.runOutput.textContent = formatJson({ summary: response.summary, mermaid: response.mermaid });
    }
  }

  async function saveDefinition() {
    const body = {
      name: els.definitionName.value.trim() || "智能体工作流",
      description: "来自 AI 智能体工作台",
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
    if (!state.definitionId) {
      toast("发布前请先保存工作流", true);
      return;
    }
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
    const input = parseJsonInput(els.workflowInput.value, {});
    await runCommand({
      request: () => requestJson(API.runWorkflow, {
        method: "POST",
        body: { workflowDefinition: buildWorkflowDefinition(), input }
      }),
      outputEl: els.runOutput,
      onSuccess: async (response) => {
        state.lastRunId = response.runId;
        setWorkflowStatus(response.runId ? `Run ${response.runId.slice(0, 8)}` : "Ran");
        await refreshRunTrace(response.runId);
        await loadRuns();
        await loadDefinitionHistory();
        toast("工作流运行完成");
      },
      onError: (error) => {
        setWorkflowStatus("Run failed");
        toast(error.message, true);
      }
    });
  }

  async function sendChat() {
    els.chatModePill.textContent = "同步";
    await runCommand({
      request: () => requestJson(API.chat, {
        method: "POST",
        body: { conversationId: "workbench", message: els.chatMessage.value }
      }),
      outputEl: els.chatOutput,
      errorToast: false
    });
  }

  async function streamChat() {
    els.chatModePill.textContent = "流式中";
    els.chatOutput.textContent = "";
    const answerParts = [];
    try {
      const response = await fetch(API.chatStream, {
        method: "POST",
        headers: authHeaders({
          Accept: "text/event-stream",
          "Content-Type": "application/json"
        }),
        body: JSON.stringify({ conversationId: "workbench", message: els.chatMessage.value })
      });
      if (!response.ok) {
        throw new Error(`HTTP ${response.status}`);
      }
      await consumeSse(response, (eventName, data) => {
        if (eventName === "message" && data?.delta) {
          answerParts.push(data.delta);
          els.chatOutput.textContent = answerParts.join("");
        }
        if (eventName === "done") {
          els.chatOutput.textContent = formatJson(data);
        }
        if (eventName === "error") {
          throw new Error(data?.error || data?.message || "Stream failed");
        }
      });
      toast("流式完成");
    } catch (error) {
      els.chatOutput.textContent = formatJson({ error: error.message });
      toast(error.message, true);
    } finally {
      els.chatModePill.textContent = "同步";
    }
  }

  async function sendToolChat() {
    await runCommand({
      request: () => requestJson(API.toolChat, {
        method: "POST",
        body: { conversationId: "workbench-agent", message: els.toolChatMessage.value }
      }),
      outputEl: els.toolChatOutput,
      errorToast: false,
      onSuccess: () => void loadRuns()
    });
  }

  async function saveDocument() {
    await runCommand({
      request: () => requestJson(API.saveDocument, {
        method: "POST",
        body: { title: els.documentTitle.value, content: els.documentContent.value }
      }),
      outputEl: els.ragOutput,
      successToast: "文档已保存",
      onSuccess: async () => {
        await Promise.allSettled([loadDocuments(), loadHealth()]);
      }
    });
  }

  async function loadDocuments() {
    try {
      const page = await requestJson(`${API.listDocuments}?page=0&size=20`);
      const documents = page?.content || [];
      renderDataList(els.documentList, documents, (document) => ({
        title: document.title || `Document ${document.id}`,
        meta: `#${document.id} · ${document.indexStatus || "UNKNOWN"} · ${document.contentLength || 0} chars · ${document.createdAt || ""}`
      }));
      if (state.health) {
        updateRagHint(state.health);
      }
    } catch (error) {
      renderDataList(els.documentList, [], () => ({ title: "无法加载文档", meta: error.message }));
    }
  }

  async function askRag() {
    await runCommand({
      request: () => requestJson(API.ragChat, {
        method: "POST",
        body: { conversationId: "workbench-rag", message: els.ragMessage.value }
      }),
      outputEl: els.ragOutput,
      errorToast: false
    });
  }

  async function runCommand({ request, onSuccess, onError, outputEl, successToast, errorToast = true }) {
    try {
      const response = await request();
      if (outputEl) {
        outputEl.textContent = formatJson(response);
      }
      if (onSuccess) {
        await onSuccess(response);
      }
      if (successToast) {
        toast(successToast);
      }
      return response;
    } catch (error) {
      if (outputEl) {
        outputEl.textContent = formatJson({ error: error.message });
      }
      if (onError) {
        onError(error);
      } else if (errorToast) {
        toast(error.message, true);
      }
      return null;
    }
  }

  async function refreshRunTrace(runId) {
    if (!runId) {
      return;
    }
    try {
      const [steps, graph] = await Promise.allSettled([
        requestJson(API.runSteps(runId)),
        requestJson(API.workflowRunGraph(runId))
      ]);
      if (steps.status === "fulfilled") {
        renderTraceSteps(steps.value, graph.status === "fulfilled" ? graph.value : null);
      }
      if (graph.status === "fulfilled") {
        applyGraphStatuses(graph.value);
      }
    } catch (error) {
      toast(error.message, true);
    }
  }

  function applyGraphStatuses(graph) {
    const statusByNode = new Map();
    (graph.nodes || []).forEach((node) => {
      statusByNode.set(node.id, node.status || "NOT_EXECUTED");
      if (node.iterations != null && node.compositeRole === "LOOP") {
        statusByNode.set(node.id, `${node.status || "NOT_EXECUTED"} · x${node.iterations}`);
      }
      (node.children || []).forEach((child) => {
        if (child.id) {
          statusByNode.set(child.id, child.status || "NOT_EXECUTED");
        }
      });
    });
    document.querySelectorAll(".canvas-node").forEach((element) => {
      const status = statusByNode.get(element.dataset.nodeId);
      if (status) {
        element.title = `状态：${status}`;
      }
    });
  }

  async function loadTools() {
    const [tools, servers] = await Promise.allSettled([
      requestJson(API.tools),
      requestJson(API.mcpServers)
    ]);
    renderDataList(els.toolList, tools.status === "fulfilled" ? tools.value : [], (tool) => ({
      title: tool.name,
      meta: `${tool.provider || "local"} · remote=${Boolean(tool.remote)}`
    }));
    const serverItems = servers.status === "fulfilled" ? servers.value : [];
    if (serverItems.length === 0 && state.health && !state.health.mcpEnabled) {
      renderDataList(els.mcpServerList, [{
        name: "MCP disabled",
        transport: "n/a",
        enabled: false,
        hint: "设置 DEMO_MCP_ENABLED=true 并配置 spring.ai.mcp.client.* 以启用远程工具。"
      }], (server) => ({
        title: server.name,
        meta: server.hint || `${server.transport || "stdio"} · enabled=${Boolean(server.enabled)}`
      }));
      return;
    }
    renderDataList(els.mcpServerList, serverItems, (server) => ({
      title: server.name,
      meta: `${server.transport || "stdio"} · enabled=${Boolean(server.enabled)}`
    }));
  }

  async function loadDefinitionHistory() {
    if (!state.definitionId) {
      renderDefinitionHistory([], []);
      return;
    }
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
      const emptyRevision = document.createElement("div");
      emptyRevision.className = "empty-state";
      emptyRevision.textContent = "保存工作流后查看历史";
      els.revisionList.appendChild(emptyRevision);
      const emptyRuns = document.createElement("div");
      emptyRuns.className = "empty-state";
      emptyRuns.textContent = "保存工作流后查看历史";
      els.workflowRunList.appendChild(emptyRuns);
      return;
    }
    if (!revisions || revisions.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "暂无版本";
      els.revisionList.appendChild(empty);
    } else {
      revisions.forEach((revision) => {
        const row = document.createElement("div");
        row.className = "history-row";
        const meta = document.createElement("div");
        meta.innerHTML = `<strong>v${revision.version}</strong> <span class="history-row-meta">${revision.status} · ${revision.updatedAt || revision.createdAt || ""}</span>`;
        const actions = document.createElement("div");
        actions.className = "history-row-actions";
        const loadButton = document.createElement("button");
        loadButton.type = "button";
        loadButton.className = "secondary-button";
        loadButton.textContent = "载入";
        loadButton.addEventListener("click", () => loadRevisionOntoCanvas(revision));
        const rollbackButton = document.createElement("button");
        rollbackButton.type = "button";
        rollbackButton.className = "secondary-button";
        rollbackButton.textContent = "回滚";
        rollbackButton.addEventListener("click", () => void rollbackDefinitionVersion(revision.version));
        actions.append(loadButton, rollbackButton);
        row.append(meta, actions);
        els.revisionList.appendChild(row);
      });
    }
    if (!runs || runs.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "该定义暂无运行记录";
      els.workflowRunList.appendChild(empty);
    } else {
      runs.forEach((run) => {
        const row = document.createElement("div");
        row.className = "history-row clickable";
        row.innerHTML = `<div><strong>${escapeHtml(run.status)}</strong><div class="history-row-meta">v${run.definitionVersion} · ${run.runId} · ${run.startedAt || ""}</div></div>`;
        row.addEventListener("click", () => void selectWorkflowRun(run));
        els.workflowRunList.appendChild(row);
      });
    }
  }

  function loadRevisionOntoCanvas(revision) {
    if (!revision?.workflowDefinition) {
      return;
    }
    hydrateWorkflow(revision.workflowDefinition);
    state.definitionVersion = revision.version;
    state.definitionStatus = revision.status;
    setWorkflowStatus(`${revision.status || "DRAFT"} v${revision.version} (loaded)`);
    saveCanvasPositions();
    renderAll();
    toast(`已载入版本 v${revision.version} 到画布`);
  }

  async function rollbackDefinitionVersion(version) {
    if (!state.definitionId) {
      return;
    }
    if (!window.confirm(`Rollback to revision v${version}? This creates a new draft version.`)) {
      return;
    }
    await runCommand({
      request: () => requestJson(API.rollbackDefinition(state.definitionId, version), { method: "POST" }),
      onSuccess: async (response) => {
        state.definitionVersion = response.version;
        state.definitionStatus = response.status;
        hydrateWorkflow(response.workflowDefinition);
        setWorkflowStatus(`${response.status || "DRAFT"} v${response.version} (rollback)`);
        saveCanvasPositions();
        renderAll();
        await loadDefinitions();
        els.definitionSelect.value = response.definitionId;
        await loadDefinitionHistory();
        toast(`已回滚到 v${version}（生成 v${response.version}）`);
      },
      onError: (error) => toast(error.message, true)
    });
  }

  async function selectWorkflowRun(run) {
    if (!run?.runId) {
      return;
    }
    state.lastRunId = run.runId;
    setWorkflowStatus(`Run ${run.runId.slice(0, 8)} · ${run.status}`);
    await refreshRunTrace(run.runId);
    toast(`已载入运行 ${run.runId.slice(0, 8)}`);
  }

  async function loadRuns() {
    try {
      const runsPage = await requestJson(`${API.runs}?page=0&size=20`);
      const runs = runsPage?.content || [];
      renderDataList(els.runList, runs, (run) => ({
        title: `${run.type} · ${run.status}`,
        meta: `${run.runId} · ${run.startedAt || ""}`
      }));
    } catch (error) {
      renderDataList(els.runList, [], () => ({ title: "暂无运行记录", meta: error.message }));
    }
  }

  function buildWorkflowDefinition() {
    return {
      nodes: state.nodes.map((node) => ({
        id: node.id,
        type: node.type,
        config: cleanConfig(node.config)
      })),
      edges: state.edges.map((edge) => {
        const payload = { from: edge.from, to: edge.to };
        if (edge.condition) {
          payload.condition = edge.condition;
        }
        return payload;
      })
    };
  }

  function cleanConfig(config) {
    const cleaned = {};
    Object.entries(config || {}).forEach(([key, value]) => {
      if (value === "" || value === null || value === undefined) {
        return;
      }
      cleaned[key] = value;
    });
    return cleaned;
  }

  async function requestJson(url, options = {}) {
    const init = {
      method: options.method || "GET",
      headers: authHeaders({ Accept: "application/json" })
    };
    if (options.body !== undefined) {
      init.headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(options.body);
    }
    const response = await fetch(url, init);
    const text = await response.text();
    const payload = text ? JSON.parse(text) : null;
    if (!response.ok || payload?.success === false) {
      throw new Error(payload?.message || payload?.code || `HTTP ${response.status}`);
    }
    return payload?.data ?? payload;
  }

  async function consumeSse(response, onEvent) {
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      buffer = drainSseBuffer(buffer, onEvent);
    }
    drainSseBuffer(`${buffer}\n\n`, onEvent);
  }

  function drainSseBuffer(buffer, onEvent) {
    const blocks = buffer.split("\n\n");
    const remainder = blocks.pop() || "";
    blocks.forEach((block) => {
      if (!block.trim()) {
        return;
      }
      let eventName = "message";
      const dataLines = [];
      block.split("\n").forEach((line) => {
        if (line.startsWith("event:")) {
          eventName = line.slice(6).trim();
        } else if (line.startsWith("data:")) {
          dataLines.push(line.slice(5).trim());
        }
      });
      if (dataLines.length === 0) {
        return;
      }
      try {
        onEvent(eventName, JSON.parse(dataLines.join("\n")));
      } catch (error) {
        onEvent(eventName, { message: dataLines.join("\n") });
      }
    });
    return remainder;
  }

  function renderTraceSteps(steps, graph) {
    els.traceSteps.innerHTML = "";
    if (!steps || steps.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "暂无步骤";
      els.traceSteps.appendChild(empty);
      return;
    }
    const childStepIds = new Set();
    const containers = buildCompositeContainers(graph, steps);
    containers.forEach((container) => {
      appendTraceContainer(els.traceSteps, container);
      container.childStepIds.forEach((stepId) => childStepIds.add(stepId));
    });
    steps.forEach((step) => {
      if (childStepIds.has(step.nodeName)) {
        return;
      }
      appendTraceStepItem(els.traceSteps, step, 0);
    });
  }

  function buildCompositeContainers(graph, steps) {
    if (graph && graph.nodes) {
      return graph.nodes
        .filter((node) => (node.children || []).length > 0)
        .map((node) => {
          const children = (node.children || []).map((child) => {
            const stepName = `workflow_node_${child.id}`;
            return { ...child, step: steps.find((step) => step.nodeName === stepName) || null };
          });
          const childStepIds = children.map((child) => `workflow_node_${child.id}`);
          const iterationLabel = node.compositeRole === "LOOP" && node.iterations != null
            ? ` · x${node.iterations} iterations`
            : "";
          return {
            title: `${node.id} (${node.type})${iterationLabel}`,
            children,
            childStepIds
          };
        });
    }
    return buildCompositeContainersFromSteps(steps);
  }

  function buildCompositeContainersFromSteps(steps) {
    if (!steps || steps.length === 0) {
      return [];
    }
    const groups = new Map();
    const claimStep = (groupKey, title, step, childId) => {
      if (!groups.has(groupKey)) {
        groups.set(groupKey, { title, children: [], childStepIds: [] });
      }
      const group = groups.get(groupKey);
      if (group.childStepIds.includes(step.nodeName)) {
        return;
      }
      group.children.push({
        id: childId,
        status: step.status,
        stepId: step.stepId,
        type: title.includes("(") ? title.split("(")[1].replace(")", "") : "step",
        step
      });
      group.childStepIds.push(step.nodeName);
    };

    steps.forEach((step) => {
      const nodeId = traceStepNodeId(step.nodeName);
      if (!nodeId) {
        return;
      }
      const subgraphSep = nodeId.indexOf("::");
      const dynamicSep = nodeId.indexOf(":dynamic:");
      if (subgraphSep > 0) {
        const parentId = nodeId.substring(0, subgraphSep);
        claimStep(`subgraph:${parentId}`, `${parentId} (subgraph)`, step, nodeId);
        return;
      }
      if (dynamicSep > 0) {
        const parentId = nodeId.substring(0, dynamicSep);
        claimStep(`dynamic:${parentId}`, `${parentId} (dynamic)`, step, nodeId);
      }
    });

    state.nodes.filter((node) => node.type === "loop").forEach((loopNode) => {
      const loopStepName = `workflow_node_${loopNode.id}`;
      if (!steps.some((step) => step.nodeName === loopStepName)) {
        return;
      }
      const bodyStart = state.edges.find((edge) => edge.from === loopNode.id && edge.condition === "body");
      if (!bodyStart) {
        return;
      }
      collectLinearChain(bodyStart.to, new Set([loopNode.id])).forEach((bodyNodeId) => {
        const bodyStep = steps.find((step) => step.nodeName === `workflow_node_${bodyNodeId}`);
        if (bodyStep) {
          claimStep(`loop:${loopNode.id}`, `${loopNode.id} (loop)`, bodyStep, bodyNodeId);
        }
      });
      const loopBackId = findLoopBackId(loopNode.id);
      if (loopBackId) {
        const loopBackStep = steps.find((step) => step.nodeName === `workflow_node_${loopBackId}`);
        if (loopBackStep) {
          claimStep(`loop:${loopNode.id}`, `${loopNode.id} (loop)`, loopBackStep, loopBackId);
        }
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
        if (syntheticStep) {
          claimStep(`parallel:${parallelNode.id}`, `${parallelNode.id} (parallel)`, syntheticStep, syntheticId);
          grouped = true;
        }
        collectLinearChain(edge.to, new Set([parallelNode.id])).forEach((branchNodeId) => {
          const branchStep = steps.find((step) => step.nodeName === `workflow_node_${branchNodeId}`);
          if (branchStep) {
            claimStep(`parallel:${parallelNode.id}`, `${parallelNode.id} (parallel)`, branchStep, branchNodeId);
            grouped = true;
          }
        });
      });
      if (!grouped && hasParallel) {
        steps.forEach((step) => {
          const nodeId = traceStepNodeId(step.nodeName);
          if (nodeId.startsWith("workflow_branch_") && nodeId.includes(`_${parallelNode.id}_`)) {
            claimStep(`parallel:${parallelNode.id}`, `${parallelNode.id} (parallel)`, step, nodeId);
          }
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
      if (!node) {
        break;
      }
      if (node.type === "loop_back" || node.type === "join") {
        break;
      }
      ids.push(current);
      const nextEdge = state.edges.find((edge) => edge.from === current);
      if (!nextEdge) {
        break;
      }
      current = nextEdge.to;
    }
    return ids;
  }

  function findLoopBackId(loopId) {
    const bodyEdge = state.edges.find((edge) => edge.from === loopId && edge.condition === "body");
    if (!bodyEdge) {
      return null;
    }
    let current = bodyEdge.to;
    const visited = new Set();
    while (current && !visited.has(current)) {
      visited.add(current);
      const node = state.nodes.find((item) => item.id === current);
      if (node?.type === "loop_back") {
        return node.id;
      }
      const nextEdge = state.edges.find((edge) => edge.from === current);
      current = nextEdge?.to;
    }
    return null;
  }

  function traceStepNodeId(nodeName) {
    if (!nodeName) {
      return "";
    }
    return nodeName.startsWith("workflow_node_") ? nodeName.slice("workflow_node_".length) : nodeName;
  }

  function appendTraceContainer(container, composite) {
    const group = document.createElement("div");
    group.className = "step-item step-group";
    const title = document.createElement("strong");
    title.textContent = composite.title;
    group.appendChild(title);
    composite.children.forEach((child) => {
      if (child.step) {
        appendTraceStepItem(group, child.step, 1);
        return;
      }
      const item = document.createElement("div");
      item.className = "step-item step-child";
      const childTitle = document.createElement("strong");
      childTitle.textContent = `${child.id} · ${child.status || "NOT_EXECUTED"}`;
      const meta = document.createElement("div");
      meta.className = "item-meta";
      meta.textContent = child.stepId || child.type || "";
      item.append(childTitle, meta);
      group.appendChild(item);
    });
    container.appendChild(group);
  }

  function appendTraceStepItem(container, step, indentLevel) {
    const item = document.createElement("div");
    item.className = indentLevel > 0 ? "step-item step-child" : "step-item";
    const title = document.createElement("strong");
    title.textContent = `${step.nodeName} · ${step.status}`;
    const meta = document.createElement("div");
    meta.className = "item-meta";
    meta.textContent = step.stepId;
    item.append(title, meta);
    const output = parseStepOutput(step);
    const usage = findTokenUsage(output);
    const summary = tokenUsageSummary(usage);
    if (summary) {
      const usageMeta = document.createElement("div");
      usageMeta.className = "item-meta token-usage";
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

  function parseStepOutput(step) {
    if (!step || !step.outputJson) {
      return null;
    }
    try {
      return JSON.parse(step.outputJson);
    } catch (error) {
      return step.outputJson;
    }
  }

  function findTokenUsage(output) {
    if (!output || typeof output !== "object") {
      return null;
    }
    if (output.tokenUsage) {
      return output.tokenUsage;
    }
    if (output.output && typeof output.output === "object" && output.output.tokenUsage) {
      return output.output.tokenUsage;
    }
    return null;
  }

  function tokenUsageSummary(usage) {
    if (!usage) {
      return "";
    }
    const model = usage.model || "unknown model";
    const prompt = usage.promptTokens ?? "-";
    const completion = usage.completionTokens ?? "-";
    const total = usage.totalTokens ?? "-";
    return `tokenUsage · ${model} · prompt ${prompt} · completion ${completion} · total ${total}`;
  }

  function renderDataList(container, items, mapper) {
    container.innerHTML = "";
    if (!items || items.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "暂无数据";
      container.appendChild(empty);
      return;
    }
    items.forEach((item) => {
      const view = mapper(item);
      const element = document.createElement("div");
      element.className = "data-item";
      const title = document.createElement("strong");
      title.textContent = view.title;
      const meta = document.createElement("div");
      meta.className = "item-meta";
      meta.textContent = view.meta || "";
      element.append(title, meta);
      container.appendChild(element);
    });
  }

  function findSelectedNode() {
    return state.nodes.find((node) => node.id === state.selectedNodeId) || null;
  }

  function schemaForType(type) {
    return state.schemas.find((schema) => schema.type === type) || fallbackSchemas.find((schema) => schema.type === type) || { type, displayName: type, configFields: [] };
  }

  function defaultConfig(schema) {
    const config = {};
    (schema.configFields || []).forEach((field) => {
      if (field.defaultValue !== null && field.defaultValue !== undefined && field.defaultValue !== "") {
        config[field.name] = cloneValue(field.defaultValue);
      }
    });
    return config;
  }

  function cloneValue(value) {
    if (Array.isArray(value) || (value && typeof value === "object")) {
      return JSON.parse(JSON.stringify(value));
    }
    return value;
  }

  function nodeSummary(node) {
    if (node.type === "llm") {
      return String(node.config.prompt || "提示词").slice(0, 54);
    }
    if (node.type === "tool") {
      return String(node.config.toolName || "getCurrentTime");
    }
    if (node.type === "retriever") {
      return `topK ${node.config.topK || 3}`;
    }
    if (node.type === "condition") {
      return `${node.config.operator || "contains"}`;
    }
    if (node.type === "subgraph") {
      return node.config.definitionId ? `def ${node.config.definitionId}` : "subgraph";
    }
    if (node.type === "loop") {
      return `max ${node.config.maxIterations || 10}`;
    }
    if (node.type === "dynamic") {
      return String(node.config.itemsFrom || "items").slice(0, 40);
    }
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
      const select = document.createElement("select");
      select.className = "text-input";
      appendOption(select, "true", "true");
      appendOption(select, "false", "false");
      select.value = String(Boolean(value));
      return select;
    }
    if (type === "integer" || type === "number") {
      const input = document.createElement("input");
      input.className = "text-input";
      input.type = "number";
      input.value = value ?? field.defaultValue ?? 0;
      if (field.constraints?.min !== undefined) {
        input.min = field.constraints.min;
      }
      if (field.constraints?.max !== undefined) {
        input.max = field.constraints.max;
      }
      return input;
    }
    if (type === "object" || type === "any") {
      const textarea = document.createElement("textarea");
      textarea.className = "code-input";
      textarea.value = typeof value === "object" ? formatJson(value) : (value ?? "");
      return textarea;
    }
    const textarea = document.createElement("textarea");
    textarea.className = "code-input";
    textarea.value = value ?? field.defaultValue ?? "";
    return textarea;
  }

  function textControl(value) {
    const input = document.createElement("input");
    input.className = "text-input";
    input.type = "text";
    input.value = value || "";
    return input;
  }

  function selectNodeControl(value) {
    const select = document.createElement("select");
    select.className = "text-input";
    state.nodes.forEach((node) => appendOption(select, node.id, node.id));
    select.value = value;
    return select;
  }

  function parseControlValue(value, type) {
    if (type === "integer") {
      return Number.parseInt(value || "0", 10);
    }
    if (type === "number") {
      return Number(value || 0);
    }
    if (type === "boolean") {
      return value === "true";
    }
    if (type === "object") {
      return parseJsonInput(value, {});
    }
    if (type === "any") {
      return parseAnyValue(value);
    }
    return value;
  }

  function parseAnyValue(value) {
    const trimmed = String(value || "").trim();
    if (!trimmed) {
      return "";
    }
    try {
      return JSON.parse(trimmed);
    } catch (error) {
      return value;
    }
  }

  function parseJsonInput(value, fallback) {
    try {
      return JSON.parse(value || "{}");
    } catch (error) {
      toast("JSON 输入无效", true);
      return fallback;
    }
  }

  function getNodeCenter(nodeId, canvasRect, side) {
    const element = els.nodeLayer.querySelector(`[data-node-id="${cssEscape(nodeId)}"]`);
    if (!element) {
      return null;
    }
    const rect = element.getBoundingClientRect();
    return {
      x: side === "right" ? rect.right - canvasRect.left : rect.left - canvasRect.left,
      y: rect.top - canvasRect.top + rect.height / 2
    };
  }

  function setWorkflowStatus(value) {
    const exact = {
      DRAFT: "草稿", PUBLISHED: "已发布", Draft: "草稿",
      Valid: "有效", Invalid: "无效", Ran: "已运行", "Run failed": "运行失败"
    };
    const label = exact[value] != null
      ? exact[value]
      : String(value)
          .replace(/\bDRAFT\b/g, "草稿")
          .replace(/\bPUBLISHED\b/g, "已发布")
          .replace(/\bRun\b/g, "运行")
          .replace(/\(loaded\)/g, "（已载入）")
          .replace(/\(rollback\)/g, "（回滚）");
    els.workflowStatus.textContent = label;
  }

  function colorForType(type) {
    return nodePaletteColors[type] || "var(--node-flow)";
  }

  function appendOption(select, value, label) {
    const option = document.createElement("option");
    option.value = value;
    option.textContent = label;
    select.appendChild(option);
  }

  function formatJson(value) {
    return JSON.stringify(value ?? {}, null, 2);
  }

  function escapeHtml(value) {
    return String(value ?? "")
      .replace(/&/g, "&amp;")
      .replace(/</g, "&lt;")
      .replace(/>/g, "&gt;")
      .replace(/"/g, "&quot;")
      .replace(/'/g, "&#039;");
  }

  function cssEscape(value) {
    if (window.CSS && typeof window.CSS.escape === "function") {
      return window.CSS.escape(value);
    }
    return String(value).replace(/"/g, "\\\"");
  }

  function requiredElement(id) {
    const element = document.getElementById(id);
    if (!element) {
      throw new Error(`Missing required element: #${id}`);
    }
    return element;
  }

  function toCamel(value) {
    return value.replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
  }

  function clamp(value, min, max) {
    return Math.max(min, Math.min(max, value));
  }

  function toast(message, isError = false) {
    window.clearTimeout(state.toastTimer);
    els.toast.textContent = message;
    els.toast.classList.toggle("error", isError);
    els.toast.classList.add("visible");
    state.toastTimer = window.setTimeout(() => {
      els.toast.classList.remove("visible");
    }, 2600);
  }
})();
