"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};

  const AUTO_STRUCTURED_HIDDEN_FIELDS = new Set(["outputMode", "outputSchema", "writeState", "autoStructuredOutputContract"]);
  const CUSTOMER_SERVICE_INTENT_CONTRACT = "customer_service_intent";
  const CUSTOMER_SERVICE_INTENTS = [
    "order_policy", "order_query", "need_order_id", "product_consult",
    "complaint", "human_transfer", "bug_feedback", "sales_lead", "chitchat"
  ];
  const CUSTOMER_SERVICE_SCHEMA_FIELDS = ["intent", "hasOrderId", "needsOrderId", "orderIds", "confidence"];
  const COMMON_EXECUTION_FIELDS = new Set(["writeState", "retryCount", "timeoutMs"]);
  const TOOL_ARGUMENT_FIELD = {
    name: "arguments",
    type: "object",
    defaultValue: {},
    constraints: { templateVariables: TEMPLATE_VARIABLES }
  };
  const NODE_PANEL_RENDERERS = {
    llm: renderLlmSettingsPanel,
    tool: renderToolSettingsPanel,
    http_request: renderHttpRequestSettingsPanel,
    report_export: renderReportExportSettingsPanel,
    custom: renderCustomSettingsPanel,
    retriever: renderRetrieverSettingsPanel,
    tavily_search: renderTavilySearchSettingsPanel,
    condition: renderConditionSettingsPanel,
    variable_aggregator: renderVariableAggregatorSettingsPanel,
    loop: renderLoopSettingsPanel,
    subgraph: renderSubgraphSettingsPanel,
    dynamic: renderDynamicSettingsPanel
  };
  const LOCAL_TOOL_ARGUMENT_PRESETS = {
    getCurrentTime: {
      label: "当前时间",
      description: "无需参数，运行时返回服务器当前时间。"
    },
    calculate: {
      label: "计算器",
      description: "执行安全四则运算表达式。",
      args: [{ key: "expression", label: "计算表达式", placeholder: "(1 + 2) * 3" }]
    },
    queryOrderAPI: {
      label: "订单查询",
      description: "从演示订单库查询订单状态；通常传入用户原始问题或订单号。",
      args: [{ key: "user_query", label: "订单问题 / 订单号", placeholder: "{{input.message}}" }]
    }
  };
  let toolCatalogRequest = null;
  const MAX_UNDO_STEPS = 80;
  let restoringWorkflowSnapshot = false;
  let pendingWorkflowSpec = null;
  let currentWorkflowLockedSpec = null;
  let latestWorkflowRepairError = "";
  let workflowGeneratorMode = "design";
  let publishDefinitionInFlight = false;

  const baseWorkflowRequestJson = requestJson;
  requestJson = async function trackedWorkflowRequestJson(url, options = {}) {
    const response = await baseWorkflowRequestJson(url, options);
    trackWorkflowLockedSpecPayload(response);
    return response;
  };
  window.AgentWorkbench.requestJson = requestJson;
  window.AgentWorkbench.helpers.requestJson = requestJson;

  function trackWorkflowLockedSpecPayload(payload) {
    if (Array.isArray(payload)) {
      payload.forEach(trackWorkflowLockedSpecPayload);
      return;
    }
    if (!payload || typeof payload !== "object" || !payload.workflowDefinition) return;
    if (!Object.prototype.hasOwnProperty.call(payload, "lockedSpec")) return;
    Object.defineProperty(payload.workflowDefinition, "__workflowLockedSpec", {
      value: canonicalWorkflowLockedSpec(payload.lockedSpec),
      configurable: true,
      enumerable: false
    });
  }

  function canonicalWorkflowLockedSpec(value) {
    if (value === null || value === undefined || value === "") return null;
    if (typeof value === "string") {
      const text = value.trim();
      if (!text) return null;
      try { return canonicalWorkflowLockedSpec(JSON.parse(text)); }
      catch (error) { return text; }
    }
    if (Array.isArray(value)) return value.map(canonicalWorkflowLockedSpec);
    if (typeof value === "object") {
      return Object.keys(value).sort((left, right) => left.localeCompare(right)).reduce((result, key) => {
        result[key] = canonicalWorkflowLockedSpec(value[key]);
        return result;
      }, {});
    }
    return value;
  }

  function restoreWorkflowLockedSpec(source) {
    if (!source || typeof source !== "object") return false;
    if (Object.prototype.hasOwnProperty.call(source, "lockedSpec")) {
      currentWorkflowLockedSpec = canonicalWorkflowLockedSpec(source.lockedSpec);
      return true;
    }
    const definition = source.workflowDefinition || source;
    if (Object.prototype.hasOwnProperty.call(definition, "__workflowLockedSpec")) {
      currentWorkflowLockedSpec = canonicalWorkflowLockedSpec(definition.__workflowLockedSpec);
      return true;
    }
    return false;
  }

  // ============================================================
  // 控制器（保留全局名，前端资源测试依赖）
  // ============================================================
  class WorkflowCanvasController {
    init() {
      cacheElements();
      bindNavigation();
      bindRuntimeChip();
      bindWorkflowActions();
      bindRouteMap();
      bindCanvasInteractions();
      bindRunDrawer();
      bindChat();
      bindLibrary();
      bindRuns();
      bindTools();
      bindApps();
      bindKnowledge();
      bindSettings();
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
  AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || [];
  AgentWorkbench.loadedModules.push("workflow");
  AgentWorkbench.controllers = AgentWorkbench.controllers || {};
  AgentWorkbench.WorkflowCanvasController = WorkflowCanvasController;
  AgentWorkbench.workflowController = AgentWorkbench.controllers.workflow = new WorkflowCanvasController();
  window.WorkflowCanvasController = AgentWorkbench.workflowController;
  window.AgentWorkbench.WorkflowCanvasController = WorkflowCanvasController;
  window.AgentWorkbench.workflowController = window.WorkflowCanvasController;

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
    els.undoWorkflow?.addEventListener("click", undoLastWorkflowEdit);
    els.repairWorkflow?.addEventListener("click", () => openWorkflowRepairPanel());
    els.saveDefinition?.addEventListener("click", () => void saveDefinition());
    els.definitionName?.addEventListener("input", () => syncWorkflowNameInputs(els.definitionName.value, els.definitionName));
    els.definitionName?.addEventListener("change", () => {
      syncWorkflowNameInputs(normalizeWorkflowName(els.definitionName.value), els.definitionName);
    });
    els.runWorkflow?.addEventListener("click", openWorkflowRunPanel);
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
        els.wfMore?.setAttribute("aria-expanded", "false");
      }
      if (els.wfIssuesPop && !els.wfIssuesPop.classList.contains("hidden")
        && !els.wfIssuesPop.contains(event.target) && !els.wfIssues.contains(event.target)) {
        els.wfIssuesPop.classList.add("hidden");
        els.wfIssues?.setAttribute("aria-expanded", "false");
      }
    });

    // 问题清单 chip
    els.wfIssues?.addEventListener("click", (event) => {
      event.stopPropagation();
      const hidden = els.wfIssuesPop.classList.toggle("hidden");
      els.wfIssues.setAttribute("aria-expanded", String(!hidden));
    });
    const closeMenu = () => {
      els.wfMenu?.classList.add("hidden");
      els.wfMore?.setAttribute("aria-expanded", "false");
    };
    els.newWorkflow?.addEventListener("click", () => {
      closeMenu(); resetWorkflow(); renderAll(); void loadDefinitionHistory(); toast("已新建工作流");
    });
    els.generateWorkflow?.addEventListener("click", () => { closeMenu(); openWorkflowGenerator(); });
    els.validateWorkflow?.addEventListener("click", () => { closeMenu(); void validateWorkflow(); });
    els.insertLoopTemplate?.addEventListener("click", () => { closeMenu(); insertLoopTemplate(); });
    els.publishDefinition?.addEventListener("click", () => { closeMenu(); void publishDefinition(); });
    els.generatorClose?.addEventListener("click", () => closeWorkflowGenerator());
    els.generatorApply?.addEventListener("click", () => void generateWorkflowFromPrompt());
    els.generatorEditCurrent?.addEventListener("click", () => void editWorkflowFromPrompt());
    els.generatorPrompt?.addEventListener("input", resetPendingWorkflowSpec);
    els.generatorPreview?.addEventListener("click", (event) => {
      const target = event.target instanceof Element
        ? event.target.closest("[data-open-tavily-settings]")
        : null;
      if (target) openTavilySettings();
    });

    // 检查器
    els.inspectorClose?.addEventListener("click", () => closeInspector());
    els.deleteNode?.addEventListener("click", deleteSelectedNode);
    els.addEdge?.addEventListener("click", addEdgeFromSelected);

    // 历史抽屉
    els.toggleHistory?.addEventListener("click", () => toggleHistory());
    els.historyClose?.addEventListener("click", () => els.definitionHistory.classList.add("hidden"));
    els.refreshDefinitionHistory?.addEventListener("click", () => void loadDefinitionHistory());

    // 面板折叠
    els.paletteCollapse?.addEventListener("click", togglePalette);
    document.addEventListener("keydown", (event) => {
      if (!(event.metaKey || event.ctrlKey) || event.shiftKey || event.key.toLowerCase() !== "z") return;
      if (isTextEditingElement(event.target)) return;
      event.preventDefault();
      undoLastWorkflowEdit();
    });
  }

  function openWorkflowRunPanel() {
    openRunDrawer();
    syncWorkflowInputWithVariables({ preserveValues: true });
    renderRunInputForm();
    window.setTimeout(focusFirstWorkflowInput, 0);
  }

  function focusFirstWorkflowInput() {
    els.runInputForm?.querySelector("input:not([type='checkbox']), textarea, select")?.focus();
  }

  function bindRouteMap() {
    els.routeMapToggle?.addEventListener("click", () => {
      state.routePanelCollapsed = !state.routePanelCollapsed;
      renderRouteMap();
    });
  }

  function togglePalette() {
    if (!els.palette || !els.paletteCollapse) return;
    const collapsed = els.palette.classList.toggle("collapsed");
    els.paletteCollapse.title = collapsed ? "展开节点面板" : "折叠";
    els.paletteCollapse.setAttribute("aria-label", collapsed ? "展开节点面板" : "折叠节点面板");
  }

  function isTextEditingElement(target) {
    if (!target || typeof target.closest !== "function") return false;
    return Boolean(target.closest("input, textarea, select, [contenteditable='true']"));
  }

  // ============================================================
  // 初始化数据
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
      restoreWorkflowLockedSpec(definition);
      els.definitionName.value = definition.name;
      hydrateWorkflow(definition.workflowDefinition);
      setWorkflowRunVariables(definition.variables, { preserveValues: false });
      bindAssistantWorkflowFromDefinition(definition);
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
    currentWorkflowLockedSpec = null;
    state.lastRunId = null;
    state.connectSourceId = null;
    state.connectSourceBranch = "";
    state.selectedNodeId = null;
    state.nodes = [
      { id: "start", type: "start", label: "开始入口", route: "", config: {} },
      { id: "retriever_1", type: "retriever", label: "知识库检索", route: "知识库问答", config: { topK: 3 } },
      { id: "llm_1", type: "llm", label: "回答生成", route: "知识库问答", config: { prompt: "Answer using this context: {{context}}\nInput: {{input}}" } },
      { id: "end", type: "end", label: "结束输出", route: "", config: {} }
    ];
    state.edges = [
      { from: "start", to: "retriever_1", condition: "", label: "进入检索", route: "知识库问答" },
      { from: "retriever_1", to: "llm_1", condition: "", label: "带上下文回答", route: "知识库问答" },
      { from: "llm_1", to: "end", condition: "", label: "输出结果", route: "知识库问答" }
    ];
    state.positions = new Map([
      ["start", { x: 80, y: 120 }],
      ["retriever_1", { x: 420, y: 120 }],
      ["llm_1", { x: 760, y: 120 }],
      ["end", { x: 1100, y: 120 }]
    ]);
    loadCanvasPositions();
    els.definitionName.value = "智能体工作流";
    setWorkflowRunVariables({
      inputs: [{
        name: "message",
        type: "string",
        required: true,
        defaultValue: "这个智能体能从文档里回答什么？",
        description: "输入给工作流的内容"
      }],
      outputs: []
    }, { preserveValues: false });
    if (els.runOutput) els.runOutput.textContent = "{}";
    if (els.runResult) { els.runResult.className = "result-card empty-result"; els.runResult.textContent = "运行后在这里查看友好结果与每一步轨迹。"; }
    if (els.traceSteps) els.traceSteps.innerHTML = "";
    closeInspector();
    els.definitionHistory?.classList.add("hidden");
    setWorkflowStatus("Draft");
    renderDefinitionHistory([], []);
    clearWorkflowUndo();
  }

  function hydrateWorkflow(definition, options = {}) {
    restoreWorkflowLockedSpec(definition);
    state.nodes = (definition.nodes || []).map((node) => ({
      id: node.id,
      type: node.type,
      label: cleanText(node.label),
      route: cleanText(node.route),
      config: { ...(node.config || {}) }
    }));
    state.edges = (definition.edges || []).map((edge) => ({
      from: edge.from,
      to: edge.to,
      condition: edge.condition || "",
      label: cleanText(edge.label),
      route: cleanText(edge.route)
    }));
    state.positions = new Map();
    state.nodes.forEach((node, index) => {
      const col = index % 4, row = Math.floor(index / 4);
      state.positions.set(node.id, { x: 80 + col * 320, y: 120 + row * 190 });
    });
    if (options.loadSavedPositions !== false) loadCanvasPositions();
    state.selectedNodeId = null;
    closeInspector();
    if (options.clearUndo !== false) clearWorkflowUndo();
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
    renderRouteMap();
    renderNodes();
    renderEdges();
    renderInspector();
    applyCanvasTransform();
    updateCanvasEmpty();
    updateUndoButton();
  }

  function captureWorkflowSnapshot() {
    return {
      nodes: cloneWorkflowValue(state.nodes),
      edges: cloneWorkflowValue(state.edges),
      positions: Array.from(state.positions.entries()).map(([nodeId, position]) => [
        nodeId,
        { x: position.x, y: position.y }
      ]),
      selectedNodeId: state.selectedNodeId,
      connectSourceId: state.connectSourceId,
      connectSourceBranch: state.connectSourceBranch,
      definitionName: els.definitionName?.value || "智能体工作流",
      lockedSpec: canonicalWorkflowLockedSpec(currentWorkflowLockedSpec),
      workflowVariables: cloneWorkflowValue(state.workflowVariables),
      workflowInput: els.workflowInput?.value || "{}"
    };
  }

  function recordWorkflowUndo(label = "编辑工作流") {
    if (restoringWorkflowSnapshot) return;
    const snapshot = captureWorkflowSnapshot();
    const snapshotKey = safeStringify(snapshot);
    const last = state.undoStack[state.undoStack.length - 1];
    if (last?.snapshotKey === snapshotKey) return;
    state.undoStack.push({ label, snapshot, snapshotKey });
    if (state.undoStack.length > MAX_UNDO_STEPS) state.undoStack.shift();
    updateUndoButton();
  }

  function applyWorkflowSnapshot(snapshot) {
    restoringWorkflowSnapshot = true;
    state.nodes = cloneWorkflowValue(snapshot.nodes || []);
    state.edges = cloneWorkflowValue(snapshot.edges || []);
    state.positions = new Map((snapshot.positions || []).map(([nodeId, position]) => [
      nodeId,
      { x: position?.x || 0, y: position?.y || 0 }
    ]));
    state.selectedNodeId = snapshot.selectedNodeId || null;
    state.connectSourceId = snapshot.connectSourceId || null;
    state.connectSourceBranch = snapshot.connectSourceBranch || "";
    currentWorkflowLockedSpec = canonicalWorkflowLockedSpec(snapshot.lockedSpec);
    if (els.definitionName) els.definitionName.value = snapshot.definitionName || "智能体工作流";
    if (els.workflowInput) els.workflowInput.value = snapshot.workflowInput || "{}";
    setWorkflowRunVariables(snapshot.workflowVariables, { preserveValues: true });
    saveCanvasPositions();
    renderAll();
    restoringWorkflowSnapshot = false;
  }

  function undoLastWorkflowEdit() {
    const entry = state.undoStack.pop();
    if (!entry) {
      updateUndoButton();
      toast("没有可撤回的操作");
      return;
    }
    applyWorkflowSnapshot(entry.snapshot);
    updateUndoButton();
    toast(`已撤回：${entry.label || "上一步操作"}`);
  }

  function clearWorkflowUndo() {
    state.undoStack = [];
    updateUndoButton();
  }

  function updateUndoButton() {
    if (!els.undoWorkflow) return;
    const count = state.undoStack.length;
    els.undoWorkflow.disabled = count === 0;
    els.undoWorkflow.title = count > 0 ? `撤回上一步（${count}）` : "撤回上一步";
    els.undoWorkflow.setAttribute("aria-label", els.undoWorkflow.title);
  }

  function cloneWorkflowValue(value) {
    return JSON.parse(JSON.stringify(value ?? null));
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

  // 分支节点（条件 / 循环）在卡片上直接展示可连线的分支行，对齐 Dify 的 IF / ELSE 出口
  function nodeBranches(node) {
    if (node.type === "condition") {
      return [
        { value: "true", tag: "IF", desc: conditionBranchDescription(node.config) || "满足" },
        { value: "false", tag: "ELSE", desc: "不满足" }
      ];
    }
    if (node.type === "loop") {
      return [
        { value: "body", tag: "循环体", desc: "满足时继续" },
        { value: "exit", tag: "退出循环", desc: "不满足时退出" }
      ];
    }
    return [];
  }

  function renderNodes() {
    els.nodeLayer.innerHTML = "";
    const routeHighlight = selectedRouteHighlight();
    state.nodes.forEach((node) => {
      const position = state.positions.get(node.id) || { x: 80, y: 80 };
      const element = document.createElement("article");
      element.className = "canvas-node";
      element.dataset.nodeId = node.id;
      element.style.left = `${position.x}px`;
      element.style.top = `${position.y}px`;
      element.classList.toggle("selected", state.selectedNodeId === node.id);
      element.classList.toggle("connecting", state.connectSourceId === node.id);
      element.classList.toggle("route-highlight", routeHighlight.active && routeHighlight.nodeIds.has(node.id));
      element.classList.toggle("route-muted", routeHighlight.active && !routeHighlight.nodeIds.has(node.id));
      const color = colorForType(node.type);
      const branches = nodeBranches(node);
      const headPort = node.type === "end" || branches.length > 0
        ? ""
        : '<button class="node-port" type="button" aria-label="从此节点连线" title="从此节点连线">+</button>';
      const branchRows = branches.map((branch) => `
        <div class="node-branch-row" data-branch="${escapeHtml(branch.value)}">
          <span class="branch-tag">${escapeHtml(branch.tag)}</span>
          <span class="branch-desc" title="${escapeHtml(branch.desc)}">${escapeHtml(branch.desc)}</span>
          <button class="node-port branch-port" type="button" data-branch="${escapeHtml(branch.value)}"
                  aria-label="从此节点连线" title="从「${escapeHtml(branch.tag)}」分支连线">+</button>
        </div>`).join("");
      const summary = nodeSummary(node);
      element.innerHTML = `
        ${node.type === "start" ? "" : '<span class="node-in-dot" aria-hidden="true"></span>'}
        <div class="node-head">
          <span class="node-ico" style="background:${color}">${iconSvg(node.type)}</span>
          <div class="node-title" title="${escapeHtml(nodeLabel(node.type))} · ${escapeHtml(node.id)}">${escapeHtml(nodeDisplayName(node))}</div>
          ${headPort}
        </div>
        <div class="node-body">
          ${summary ? `<div class="node-summary">${escapeHtml(summary)}</div>` : ""}
          ${branchRows}
        </div>`;
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
      let dragUndoRecorded = false;
      const startPos = state.positions.get(node.id) || { x: 0, y: 0 };
      element.setPointerCapture(event.pointerId);
      const move = (ev) => {
        const dx = (ev.clientX - down.x) / state.view.scale;
        const dy = (ev.clientY - down.y) / state.view.scale;
        if (!moved && Math.hypot(ev.clientX - down.x, ev.clientY - down.y) > 4) {
          moved = true; element.classList.add("dragging");
        }
        if (moved) {
          if (!dragUndoRecorded) {
            recordWorkflowUndo("移动节点");
            dragUndoRecorded = true;
          }
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

    element.querySelectorAll(".node-port").forEach((port) => {
      port.addEventListener("click", (event) => {
        event.stopPropagation();
        openBlockSelector(node, port.dataset.branch || "", port.getBoundingClientRect());
      });
    });
  }

  // ============================================================
  // 添加下一步：点节点 + 弹出选择器，选完自动创建、连线、摆位
  // （对齐 Dify：用户不需要先建节点、再理解“连线”概念）
  // ============================================================
  let blockSelectorEl = null;

  function closeBlockSelector() {
    if (!blockSelectorEl) return;
    blockSelectorEl.remove();
    blockSelectorEl = null;
    document.removeEventListener("click", onBlockSelectorOutsideClick, true);
    document.removeEventListener("keydown", onBlockSelectorKeydown, true);
  }

  function onBlockSelectorOutsideClick(event) {
    if (blockSelectorEl && !blockSelectorEl.contains(event.target)) closeBlockSelector();
  }

  function onBlockSelectorKeydown(event) {
    if (event.key === "Escape") closeBlockSelector();
  }

  function openBlockSelector(sourceNode, branch, anchorRect) {
    closeBlockSelector();
    if (state.connectSourceId) {
      state.connectSourceId = null;
      state.connectSourceBranch = "";
      renderNodes();
    }
    const pop = document.createElement("div");
    pop.className = "block-selector";
    const branchLabel = branch ? edgeDisplayName({ label: "", condition: branch }) : "";
    const head = document.createElement("div");
    head.className = "block-selector-head";
    head.textContent = branchLabel ? `添加下一步 · ${branchLabel} 分支` : "添加下一步";
    pop.appendChild(head);

    const list = document.createElement("div");
    list.className = "block-selector-list";
    state.schemas
      .filter((schema) => schema.type !== "start")
      .forEach((schema) => {
        const option = document.createElement("button");
        option.type = "button";
        option.className = "block-option";
        option.innerHTML = `
          <span class="palette-ico" style="background:${colorForType(schema.type)}">${iconSvg(schema.type)}</span>
          <span>${escapeHtml(nodeLabel(schema))}</span>`;
        option.addEventListener("click", () => {
          closeBlockSelector();
          addNextNode(sourceNode, branch, schema.type);
        });
        list.appendChild(option);
      });
    pop.appendChild(list);

    const connectExisting = document.createElement("button");
    connectExisting.type = "button";
    connectExisting.className = "block-option block-option-footer";
    connectExisting.textContent = "连接到已有节点…";
    connectExisting.addEventListener("click", () => {
      closeBlockSelector();
      state.connectSourceId = sourceNode.id;
      state.connectSourceBranch = branch || "";
      renderNodes();
      toast("请点击目标节点完成连线（点空白处取消）");
    });
    pop.appendChild(connectExisting);

    document.body.appendChild(pop);
    const rect = pop.getBoundingClientRect();
    let left = anchorRect.right + 8;
    if (left + rect.width > window.innerWidth - 12) left = anchorRect.left - rect.width - 8;
    const top = Math.max(12, Math.min(anchorRect.top - 8, window.innerHeight - rect.height - 12));
    pop.style.left = `${Math.max(12, left)}px`;
    pop.style.top = `${top}px`;
    blockSelectorEl = pop;
    window.setTimeout(() => {
      document.addEventListener("click", onBlockSelectorOutsideClick, true);
      document.addEventListener("keydown", onBlockSelectorKeydown, true);
    }, 0);
  }

  // 新节点放在来源分支出口的右侧；被占位就往下顺延
  function nextNodePosition(sourceNode, branch) {
    const sourcePosition = state.positions.get(sourceNode.id) || { x: 80, y: 120 };
    const port = portWorld(sourceNode.id, "right", branch);
    const x = Math.round(sourcePosition.x + 330);
    let y = Math.round(port ? port.y - NODE_HEAD_PORT_Y : sourcePosition.y);
    let guard = 0;
    const occupied = () => Array.from(state.positions.values())
      .some((p) => Math.abs(p.x - x) < 220 && Math.abs(p.y - y) < 84);
    while (guard < 24 && occupied()) { y += 100; guard += 1; }
    return { x, y };
  }

  function addNextNode(sourceNode, branch, type) {
    recordWorkflowUndo("添加下一步");
    const baseId = type.replace(/[^a-zA-Z0-9_]/g, "_") || "node";
    const id = uniqueNodeId(baseId);
    state.nodes.push({
      id,
      type,
      label: defaultNodeLabel(type),
      route: cleanText(sourceNode.route),
      config: defaultConfig(schemaForType(type))
    });
    state.positions.set(id, nextNodePosition(sourceNode, branch));
    state.edges.push({ from: sourceNode.id, to: id, condition: branch || "", label: "", route: cleanText(sourceNode.route) });
    state.selectedNodeId = id;
    state.connectSourceId = null;
    state.connectSourceBranch = "";
    saveCanvasPositions();
    renderAll();
    ensureNodeVisible(id);
    toast(`已添加「${nodeLabel(type)}」并自动连线`);
  }

  // 新节点若落在视口外（或被右侧面板遮住），平移画布让它可见
  function ensureNodeVisible(nodeId) {
    const position = state.positions.get(nodeId);
    if (!position) return;
    const rect = els.workflowCanvas.getBoundingClientRect();
    const scale = state.view.scale;
    const margin = 40;
    const nodeLeft = position.x * scale + state.view.panX;
    const nodeTop = position.y * scale + state.view.panY;
    const nodeRight = nodeLeft + 240 * scale;
    const nodeBottom = nodeTop + 96 * scale;
    const inspectorWidth = els.nodeInspector && !els.nodeInspector.classList.contains("hidden")
      ? els.nodeInspector.offsetWidth + 24
      : 0;
    const visibleRight = rect.width - inspectorWidth;
    let moved = false;
    if (nodeRight > visibleRight - margin) { state.view.panX -= nodeRight - (visibleRight - margin); moved = true; }
    if (nodeLeft < margin) { state.view.panX += margin - nodeLeft; moved = true; }
    if (nodeBottom > rect.height - margin) { state.view.panY -= nodeBottom - (rect.height - margin); moved = true; }
    if (nodeTop < margin) { state.view.panY += margin - nodeTop; moved = true; }
    if (moved) applyCanvasTransform();
  }

  function renderEdges() {
    const svg = els.edgeLayer;
    // 保留 <defs>，清除旧路径
    svg.querySelectorAll(".edge-path, .edge-label").forEach((n) => n.remove());
    const routeHighlight = selectedRouteHighlight();
    state.edges.forEach((edge) => {
      const from = portWorld(edge.from, "right", edge.condition || "");
      const to = portWorld(edge.to, "left");
      if (!from || !to) return;
      const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
      const mid = Math.max(50, Math.abs(to.x - from.x) / 2);
      path.setAttribute("d", `M ${from.x} ${from.y} C ${from.x + mid} ${from.y}, ${to.x - mid} ${to.y}, ${to.x} ${to.y}`);
      const edgeClasses = ["edge-path"];
      if (edge.condition) edgeClasses.push("conditional");
      if (routeHighlight.active) edgeClasses.push(routeHighlight.edgeKeys.has(routeEdgeKey(edge)) ? "route-highlight" : "route-muted");
      path.setAttribute("class", edgeClasses.join(" "));
      path.setAttribute("marker-end", "url(#arrow)");
      svg.appendChild(path);
      const displayLabel = edgeDisplayName(edge);
      if (displayLabel) {
        const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
        label.setAttribute("x", String((from.x + to.x) / 2));
        label.setAttribute("y", String((from.y + to.y) / 2 - 8));
        label.setAttribute("text-anchor", "middle");
        const labelClasses = ["edge-label"];
        if (routeHighlight.active) labelClasses.push(routeHighlight.edgeKeys.has(routeEdgeKey(edge)) ? "route-highlight" : "route-muted");
        label.setAttribute("class", labelClasses.join(" "));
        label.textContent = displayLabel;
        svg.appendChild(label);
      }
    });
  }

  // ============================================================
  // 问题清单：主动告诉非专业用户“还差什么”，点击直接跳到问题节点
  // ============================================================
  function workflowIssues() {
    const issues = [];
    state.nodes.forEach((node) => {
      const outgoing = state.edges.filter((edge) => edge.from === node.id);
      const incoming = state.edges.filter((edge) => edge.to === node.id);
      const name = nodeDisplayName(node);
      if (node.type !== "start" && incoming.length === 0) {
        issues.push({ nodeId: node.id, message: `「${name}」还没有上游连线，运行时不会被执行` });
      }
      if (node.type !== "end" && node.type !== "loop_back" && outgoing.length === 0) {
        issues.push({ nodeId: node.id, message: `「${name}」还没有下一步` });
      }
      if (node.type === "condition") {
        if (!outgoing.some((edge) => (edge.condition || "") === "true")) {
          issues.push({ nodeId: node.id, message: `「${name}」的 IF 分支还没有去向` });
        }
        if (!outgoing.some((edge) => (edge.condition || "") === "false")) {
          issues.push({ nodeId: node.id, message: `「${name}」的 ELSE 分支还没有去向` });
        }
      }
      if (node.type === "loop") {
        if (!outgoing.some((edge) => (edge.condition || "") === "body")) {
          issues.push({ nodeId: node.id, message: `「${name}」的循环体还没有去向` });
        }
        if (!outgoing.some((edge) => (edge.condition || "") === "exit")) {
          issues.push({ nodeId: node.id, message: `「${name}」的退出循环还没有去向` });
        }
      }
      if (node.type === "tool" && !cleanText(node.config.toolName)) {
        issues.push({ nodeId: node.id, message: `「${name}」还没有选择要调用的工具` });
      }
      if (node.type === "llm" && !cleanText(node.config.prompt)) {
        issues.push({ nodeId: node.id, message: `「${name}」的提示词还是空的` });
      }
      if (node.type === "custom") {
        const mode = node.config?.mode === "template" ? "template" : "ai";
        if (mode === "ai" && !cleanText(node.config?.instruction)) {
          issues.push({ nodeId: node.id, message: `「${name}」还没有填写业务要求` });
        }
        if (mode === "template" && !cleanText(node.config?.template)) {
          issues.push({ nodeId: node.id, message: `「${name}」还没有填写输出模板` });
        }
        const invalidInput = Object.keys(normalizeCustomInputs(node.config?.inputs))
          .find((key) => !/^[a-zA-Z][a-zA-Z0-9_-]{0,63}$/.test(key));
        if (invalidInput) {
          issues.push({ nodeId: node.id, message: `「${name}」的输入名 ${invalidInput} 不合法` });
        }
      }
      if (node.type === "subgraph" && !cleanText(node.config.definitionId)) {
        issues.push({ nodeId: node.id, message: `「${name}」还没有选择子工作流` });
      }
    });
    return issues;
  }

  function renderIssues() {
    if (!els.wfIssues || !els.wfIssuesPop) return;
    const issues = workflowIssues();
    els.wfIssues.classList.toggle("ok", issues.length === 0);
    els.wfIssues.textContent = issues.length === 0 ? "检查通过" : `${issues.length} 项待完善`;
    els.wfIssues.title = issues.length === 0 ? "所有节点都已连好" : "点击查看待完善项";
    els.wfIssuesPop.innerHTML = "";
    if (issues.length === 0) {
      const done = document.createElement("div");
      done.className = "issue-empty";
      done.textContent = "所有节点都已连好，可以运行或保存。";
      els.wfIssuesPop.appendChild(done);
      return;
    }
    issues.forEach((issue) => {
      const item = document.createElement("button");
      item.type = "button";
      item.className = "issue-item";
      item.textContent = issue.message;
      item.addEventListener("click", () => {
        els.wfIssuesPop.classList.add("hidden");
        els.wfIssues.setAttribute("aria-expanded", "false");
        jumpToNode(issue.nodeId);
      });
      els.wfIssuesPop.appendChild(item);
    });
  }

  function jumpToNode(nodeId) {
    const position = state.positions.get(nodeId);
    if (!position) return;
    const rect = els.workflowCanvas.getBoundingClientRect();
    const scale = state.view.scale;
    state.view.panX = Math.round(rect.width / 2 - (position.x + 120) * scale);
    state.view.panY = Math.round(rect.height / 2 - (position.y + 40) * scale);
    state.selectedNodeId = nodeId;
    applyCanvasTransform();
    renderNodes();
    renderEdges();
    renderInspector();
  }

  function renderRouteMap() {
    if (!els.routeMapPanel || !els.routeMapList) return;
    // 画布任何变化都会走到这里：顺带刷新问题清单，保持提示实时
    renderIssues();
    const routes = routeSummaries();
    const visibleIds = new Set(routes.map((route) => route.id));
    Array.from(state.routeFilters).forEach((id) => { if (!visibleIds.has(id)) state.routeFilters.delete(id); });
    els.routeMapPanel.classList.toggle("collapsed", state.routePanelCollapsed);
    if (els.routeMapTitle) {
      els.routeMapTitle.textContent = state.routePanelCollapsed
        ? `${routes.length || 0} 条路径`
        : `${routes.length || 0} 条路径 · ${state.nodes.length} 节点`;
    }
    if (els.routeMapToggle) {
      els.routeMapToggle.title = state.routePanelCollapsed ? "展开路由概览" : "折叠路由概览";
      els.routeMapToggle.setAttribute("aria-label", els.routeMapToggle.title);
    }
    els.routeMapList.innerHTML = "";
    if (state.routePanelCollapsed) return;
    if (routes.length === 0) {
      els.routeMapList.appendChild(emptyDiv("暂无可识别路径"));
      return;
    }
    routes.forEach((route) => {
      const checked = state.routeFilters.has(route.id);
      const label = document.createElement("label");
      label.className = `route-filter ${checked ? "active" : ""}`;
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.checked = checked;
      checkbox.addEventListener("change", () => {
        if (checkbox.checked) state.routeFilters.add(route.id);
        else state.routeFilters.delete(route.id);
        renderRouteMap();
        renderNodes();
        renderEdges();
      });
      const main = document.createElement("span");
      main.className = "route-filter-main";
      main.innerHTML = `<span class="route-filter-title">${escapeHtml(route.label)}</span>
        <span class="route-filter-path">${escapeHtml(route.pathText)}</span>`;
      const count = document.createElement("span");
      count.className = "route-filter-count";
      count.textContent = `${route.nodeIds.size} 节点`;
      label.append(checkbox, main, count);
      els.routeMapList.appendChild(label);
    });
  }

  function routeSummaries() {
    const explicitRoutes = explicitRouteSummaries();
    const explicitLabels = new Set(explicitRoutes.map((route) => normalizeRouteText(route.label)));
    const matchedRoutes = ROUTE_RULES
      .map((rule) => ({ ...rule, ...routeMatchForRule(rule) }))
      .filter((route) => route.nodeIds.size > 0)
      .filter((route) => !explicitLabels.has(normalizeRouteText(route.label)))
      .map((route) => ({ ...route, pathText: routePathText(route.nodeIds) }));
    const routes = [...explicitRoutes, ...matchedRoutes];
    if (routes.length > 0 || state.nodes.length === 0) return routes;
    const nodeIds = new Set(state.nodes.map((node) => node.id));
    const edgeKeys = new Set(state.edges.map(routeEdgeKey));
    return [{
      id: "canvas-default",
      label: "默认路径",
      short: "默认",
      nodeIds,
      edgeKeys,
      pathText: routePathText(nodeIds)
    }];
  }

  function explicitRouteSummaries() {
    const groups = new Map();
    const ensureGroup = (routeName) => {
      const label = cleanText(routeName);
      if (!label) return null;
      const key = normalizeRouteText(label);
      if (!groups.has(key)) {
        groups.set(key, {
          id: customRouteId(label),
          label,
          short: label.replace(/流程$/, "").slice(0, 6) || label,
          nodeIds: new Set(),
          edgeKeys: new Set()
        });
      }
      return groups.get(key);
    };

    state.nodes.forEach((node) => {
      const group = ensureGroup(node.route);
      if (group) group.nodeIds.add(node.id);
    });
    state.edges.forEach((edge) => {
      const group = ensureGroup(edge.route);
      if (!group) return;
      group.edgeKeys.add(routeEdgeKey(edge));
      group.nodeIds.add(edge.from);
      group.nodeIds.add(edge.to);
    });
    groups.forEach((group) => {
      state.edges.forEach((edge) => {
        if (group.nodeIds.has(edge.from) && group.nodeIds.has(edge.to)) {
          group.edgeKeys.add(routeEdgeKey(edge));
        }
      });
    });
    return Array.from(groups.values())
      .filter((group) => group.nodeIds.size > 0)
      .map((group) => ({ ...group, pathText: routePathText(group.nodeIds) }));
  }

  function customRouteId(routeName) {
    let hash = 0;
    Array.from(routeName).forEach((char) => {
      hash = ((hash << 5) - hash + char.charCodeAt(0)) | 0;
    });
    return `custom-route-${Math.abs(hash)}`;
  }

  function routeMatchForRule(rule) {
    const nodeIds = new Set();
    const edgeKeys = new Set();
    const includeEdge = (edge) => {
      edgeKeys.add(routeEdgeKey(edge));
      nodeIds.add(edge.from);
      nodeIds.add(edge.to);
    };
    const includeNodePath = (nodeId) => {
      nodeIds.add(nodeId);
      state.edges.filter((edge) => edge.to === nodeId).forEach((edge) => {
        includeEdge(edge);
        const fromNode = state.nodes.find((node) => node.id === edge.from);
        if (fromNode?.type === "condition" || fromNode?.type === "parallel" || fromNode?.type === "loop") nodeIds.add(edge.from);
      });
      collectRouteChain(nodeId, nodeIds, edgeKeys);
    };
    state.edges.forEach((edge) => {
      const fromNode = state.nodes.find((node) => node.id === edge.from);
      const toNode = state.nodes.find((node) => node.id === edge.to);
      const edgeText = [edge.condition, edge.label, edge.route, edge.from, edge.to, nodeCorpus(fromNode), nodeCorpus(toNode)].join(" ");
      if (matchesRouteRule(edgeText, rule)) {
        includeEdge(edge);
        collectRouteChain(edge.to, nodeIds, edgeKeys, new Set([edge.from]));
      }
    });
    state.nodes.forEach((node) => {
      if (rule.typeHints?.includes(node.type) || matchesRouteRule(nodeCorpus(node), rule)) includeNodePath(node.id);
    });
    return { nodeIds, edgeKeys };
  }

  function selectedRouteHighlight() {
    if (!state.routeFilters || state.routeFilters.size === 0) return { active: false, nodeIds: new Set(), edgeKeys: new Set() };
    const nodeIds = new Set();
    const edgeKeys = new Set();
    routeSummaries().forEach((route) => {
      if (!state.routeFilters.has(route.id)) return;
      route.nodeIds.forEach((id) => nodeIds.add(id));
      route.edgeKeys.forEach((key) => edgeKeys.add(key));
    });
    return { active: nodeIds.size > 0 || edgeKeys.size > 0, nodeIds, edgeKeys };
  }

  function collectRouteChain(startId, nodeIds, edgeKeys, visited = new Set()) {
    let current = startId;
    let guard = 0;
    while (current && !visited.has(current) && guard < 32) {
      guard += 1;
      visited.add(current);
      nodeIds.add(current);
      const outgoing = state.edges.filter((edge) => edge.from === current);
      if (outgoing.length !== 1) break;
      const edge = outgoing[0];
      edgeKeys.add(routeEdgeKey(edge));
      current = edge.to;
    }
  }

  function routePathText(nodeIds) {
    const ordered = state.nodes
      .filter((node) => nodeIds.has(node.id))
      .map((node) => nodeDisplayName(node));
    if (ordered.length === 0) return "未配置";
    const head = ordered.slice(0, 5).join(" → ");
    return ordered.length > 5 ? `${head} → +${ordered.length - 5}` : head;
  }

  function routeEdgeKey(edge) {
    return `${edge.from}=>${edge.to}:${edge.condition || ""}:${edge.label || ""}:${edge.route || ""}`;
  }

  function matchesRouteRule(value, rule) {
    const text = normalizeRouteText(value);
    return (rule.keywords || []).some((keyword) => text.includes(normalizeRouteText(keyword)));
  }

  function nodeCorpus(node) {
    if (!node) return "";
    return [node.id, node.type, node.label, node.route, nodeLabel(node.type), safeStringify(node.config)].join(" ");
  }

  function normalizeRouteText(value) {
    return String(value || "").toLowerCase();
  }

  // 节点端口的世界坐标（与缩放无关，使用未缩放布局尺寸 offsetWidth/Height）
  // 出边优先锚定在分支行（IF / ELSE / 循环体 / 退出），否则锚定在节点头部端口。
  const NODE_HEAD_PORT_Y = 26;

  function portWorld(nodeId, side, branch = "") {
    const position = state.positions.get(nodeId);
    if (!position) return null;
    const element = els.nodeLayer.querySelector(`[data-node-id="${cssEscape(nodeId)}"]`);
    const width = element ? element.offsetWidth : 240;
    if (side !== "right") return { x: position.x, y: position.y + NODE_HEAD_PORT_Y };
    if (element && branch) {
      const row = element.querySelector(`.node-branch-row[data-branch="${cssEscape(branch)}"]`);
      if (row) return { x: position.x + width, y: position.y + row.offsetTop + row.offsetHeight / 2 };
    }
    return { x: position.x + width, y: position.y + NODE_HEAD_PORT_Y };
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
      if (event.target.closest(".canvas-node") || event.target.closest(".canvas-controls") || event.target.closest(".route-map-panel")) return;
      const origin = { x: event.clientX, y: event.clientY, panX: state.view.panX, panY: state.view.panY };
      canvas.classList.add("panning");
      canvas.setPointerCapture(event.pointerId);
      if (state.connectSourceId) { state.connectSourceId = null; state.connectSourceBranch = ""; renderNodes(); }
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
    const contentW = maxX - minX || 1;
    const contentH = maxY - minY || 1;
    const innerW = Math.max(1, rect.width - pad * 2);
    const innerH = Math.max(1, rect.height - pad * 2);
    const scale = clamp(Math.min(innerW / contentW, innerH / contentH), ZOOM_MIN, 1.2);
    state.view.scale = scale;
    state.view.panX = pad - minX * scale + Math.max(0, (innerW - contentW * scale) / 2);
    state.view.panY = pad - minY * scale + Math.max(0, (innerH - contentH * scale) / 2);
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
      els.nodeInspector?.classList.add("hidden");
      if (els.inspectorTitle) els.inspectorTitle.textContent = "节点设置";
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
    const conditionNode = node.type === "condition";
    els.edgeSection.classList.toggle("hidden", conditionNode);
    els.deleteNode.disabled = node.type === "start" || node.type === "end";
    els.inspectorForm.innerHTML = "";

    els.inspectorForm.appendChild(renderWorkflowNameSettings());

    // ---- 节点头：图标 + 显示名称（就地编辑）+ 类型徽标（Dify 式面板头） ----
    const head = document.createElement("div");
    head.className = "panel-node-head";
    const headIcon = document.createElement("span");
    headIcon.className = "node-ico";
    headIcon.style.background = colorForType(node.type);
    headIcon.innerHTML = iconSvg(node.type);
    const labelInput = document.createElement("input");
    labelInput.className = "panel-node-title";
    labelInput.type = "text";
    labelInput.value = node.label || "";
    labelInput.placeholder = inferredNodeDisplayName(node);
    labelInput.setAttribute("aria-label", "显示名称");
    labelInput.title = "显示名称";
    labelInput.addEventListener("change", () => {
      if ((node.label || "") === cleanText(labelInput.value)) return;
      recordWorkflowUndo("编辑节点名称");
      node.label = cleanText(labelInput.value);
      renderRouteMap(); renderNodes(); renderEdges();
    });
    const typeChip = document.createElement("span");
    typeChip.className = "panel-node-type";
    typeChip.textContent = nodeLabel(node.type);
    head.append(headIcon, labelInput, typeChip);
    els.inspectorForm.appendChild(head);

    const schema = schemaForType(node.type);
    const sectionTitle = document.createElement("div");
    sectionTitle.className = "inspector-section-title";
    sectionTitle.textContent = "设置";
    els.inspectorForm.appendChild(sectionTitle);

    const autoStructured = isAutoStructuredOutputNode(node);
    if (autoStructured) els.inspectorForm.appendChild(renderAutoStructuredOutputNotice(node));

    if (!renderNodeSpecificSettings(node, schema, { autoStructured })) {
      renderGenericNodeSettings(node, schema, { autoStructured });
    }

    const executionControls = renderExecutionControls(node, schema, { autoStructured });
    if (executionControls) els.inspectorForm.appendChild(executionControls);
    els.inspectorForm.appendChild(renderAdvancedNodeSettings(node));
    if (conditionNode) {
      els.inspectorForm.appendChild(renderConditionBranchEditor(node));
    } else {
      renderEdgeEditor(node);
    }
  }

  function renderNodeSpecificSettings(node, schema, options = {}) {
    const renderer = NODE_PANEL_RENDERERS[node.type];
    if (!renderer) return false;
    renderer({ node, schema, autoStructured: Boolean(options.autoStructured) });
    return true;
  }

  function renderGenericNodeSettings(node, schema, options = {}) {
    (schema.configFields || [])
      .filter((field) => shouldShowPrimaryConfigField(field, options))
      .forEach((field) => els.inspectorForm.appendChild(renderSchemaConfigField(node, field)));
  }

  function shouldShowPrimaryConfigField(field, options = {}) {
    if (!field?.name) return false;
    if (COMMON_EXECUTION_FIELDS.has(field.name)) return false;
    if (options.autoStructured && AUTO_STRUCTURED_HIDDEN_FIELDS.has(field.name)) return false;
    return true;
  }

  function renderSchemaConfigField(node, field, options = {}) {
    const shell = fieldShell(options.label || configFieldLabel(field.name));
    const control = options.control || controlForField(field, node.config?.[field.name]);
    if (options.placeholder && "placeholder" in control) control.placeholder = options.placeholder;
    control.addEventListener("change", (event) => {
      event.stopPropagation();
      const nextValue = options.readValue
        ? options.readValue(control)
        : parseControlValue(control.value, field.type);
      commitNodeConfig(node, field.name, nextValue, Boolean(options.rerenderOnChange));
    });
    shell.appendChild(control);
    if (options.hint) shell.appendChild(configHint(options.hint));
    return shell;
  }

  function commitNodeConfig(node, fieldName, value, rerender = false) {
    node.config = node.config || {};
    if (safeStringify(node.config[fieldName]) === safeStringify(value)) return;
    recordWorkflowUndo("编辑节点配置");
    node.config[fieldName] = value;
    renderRouteMap();
    renderNodes();
    renderEdges();
    if (rerender) renderInspector();
  }

  function schemaConfigField(schema, name, fallback = {}) {
    const field = (schema.configFields || []).find((candidate) => candidate.name === name);
    if (!field) return { name, type: "string", defaultValue: "", ...fallback };
    return {
      name,
      ...fallback,
      ...field,
      constraints: { ...(fallback.constraints || {}), ...(field.constraints || {}) }
    };
  }

  function appendPanelConfigField(body, node, schema, name, options = {}) {
    body.appendChild(renderSchemaConfigField(node, schemaConfigField(schema, name, options.field || {}), options));
  }

  function renderWorkflowNameSettings() {
    const card = document.createElement("div");
    card.className = "workflow-name-card";
    const field = fieldShell("工作流名称");
    const input = document.createElement("input");
    input.className = "text-input workflow-name-input";
    input.type = "text";
    input.maxLength = 128;
    input.value = els.definitionName?.value || "智能体工作流";
    input.placeholder = "例如：客户评价自动分流";
    input.addEventListener("input", () => syncWorkflowNameInputs(input.value, input));
    input.addEventListener("change", () => syncWorkflowNameInputs(normalizeWorkflowName(input.value), input));
    field.appendChild(input);
    field.appendChild(configHint("保存时使用这个名称，也会显示在已保存工作流列表。"));
    card.appendChild(field);
    return card;
  }

  function normalizeWorkflowName(value) {
    return cleanText(value) || "智能体工作流";
  }

  function syncWorkflowNameInputs(value, source = null) {
    const next = String(value ?? "");
    if (els.definitionName && els.definitionName !== source) els.definitionName.value = next;
    document.querySelectorAll(".workflow-name-input").forEach((input) => {
      if (input !== source) input.value = next;
    });
  }

  function nodePanelCard(titleText, subtitleText) {
    const card = document.createElement("section");
    card.className = "node-panel-card";
    const head = document.createElement("div");
    head.className = "node-panel-head";
    const title = document.createElement("div");
    title.className = "node-panel-title";
    title.textContent = titleText;
    head.appendChild(title);
    if (subtitleText) {
      const subtitle = document.createElement("div");
      subtitle.className = "node-panel-subtitle";
      subtitle.textContent = subtitleText;
      head.appendChild(subtitle);
    }
    const body = document.createElement("div");
    body.className = "node-panel-body";
    card.append(head, body);
    return { card, body };
  }

  function configHint(text) {
    const hint = document.createElement("div");
    hint.className = "config-hint";
    hint.textContent = text;
    return hint;
  }

  const LLM_PROMPT_DEFAULT_INSTRUCTION = "请根据输入内容完成任务。";

  function renderLlmPromptBuilder(body, node) {
    const parts = extractLlmPromptParts(node.config?.prompt);
    const inputVariable = inferLlmInputVariable(node, parts);
    const shell = fieldShell("模型输入");
    const builder = document.createElement("div");
    builder.className = "llm-prompt-builder";

    const inputGroup = document.createElement("label");
    inputGroup.className = "llm-prompt-group";
    const inputLabel = document.createElement("span");
    inputLabel.textContent = "任务输入";
    const inputPicker = createVisualVariablePicker(inputVariable);
    inputGroup.append(inputLabel, inputPicker);

    const instructionGroup = document.createElement("div");
    instructionGroup.className = "llm-prompt-group";
    const instructionHead = document.createElement("div");
    instructionHead.className = "llm-prompt-head";
    const instructionLabel = document.createElement("span");
    instructionLabel.textContent = "业务指令";
    const draftButton = document.createElement("button");
    draftButton.type = "button";
    draftButton.className = "btn btn-sm btn-ghost llm-prompt-draft";
    draftButton.textContent = "AI 生成完整文案";
    const instruction = document.createElement("textarea");
    instruction.className = "code-input llm-business-prompt";
    instruction.placeholder = "先写一句粗略要求，例如：判断客户评论是正面还是负面。";
    instruction.value = parts.instruction || LLM_PROMPT_DEFAULT_INSTRUCTION;
    instructionHead.append(instructionLabel, draftButton);
    instructionGroup.append(instructionHead, instruction);

    const hint = document.createElement("div");
    hint.className = "config-hint";
    hint.textContent = "输入内容由系统自动接入；你只写业务要求，无需手写 {{state.xxx}} 或 {{input.xxx}}。";

    function commitPrompt(recordUndo = true) {
      node.config = node.config || {};
      const nextPrompt = composeLlmPrompt(inputPicker.value, instruction.value);
      if (node.config.prompt === nextPrompt) return;
      if (recordUndo) recordWorkflowUndo("编辑大模型提示词");
      node.config.prompt = nextPrompt;
      renderRouteMap();
      renderNodes();
      renderEdges();
    }

    inputPicker.addEventListener("change", (event) => {
      event.stopPropagation();
      commitPrompt();
    });
    instruction.addEventListener("change", (event) => {
      event.stopPropagation();
      commitPrompt();
    });
    draftButton.addEventListener("click", async (event) => {
      event.preventDefault();
      event.stopPropagation();
      await generateLlmBusinessInstruction({
        button: draftButton,
        instruction,
        inputPicker,
        node,
        commitPrompt
      });
    });

    if (!cleanText(node.config?.prompt)) commitPrompt(false);

    builder.append(inputGroup, instructionGroup, hint);
    shell.appendChild(builder);
    body.appendChild(shell);
  }

  async function generateLlmBusinessInstruction({ button, instruction, inputPicker, node, commitPrompt }) {
    const requirement = cleanText(instruction.value);
    if (!requirement || requirement === LLM_PROMPT_DEFAULT_INSTRUCTION) {
      toast("先写一句你希望这个节点完成什么任务", true);
      instruction.focus();
      return;
    }
    const originalText = button.textContent;
    button.disabled = true;
    button.textContent = "生成中…";
    try {
      const response = await requestJson(API.promptDraft, {
        method: "POST",
        body: {
          requirement,
          nodeLabel: node.label || node.id,
          inputLabel: selectedVariablePickerLabel(inputPicker)
        }
      });
      const generated = cleanText(response?.instruction);
      if (!generated) throw new Error("AI 没有返回可用文案");
      recordWorkflowUndo("AI 生成完整文案");
      instruction.value = generated;
      applyPromptDraftConfiguration(node, response);
      commitPrompt(false);
      renderInspector();
      toast("已生成完整业务指令");
    } catch (error) {
      toast(error.message, true);
    } finally {
      button.disabled = false;
      button.textContent = originalText;
    }
  }

  function applyPromptDraftConfiguration(node, draft) {
    node.config = node.config || {};
    if (cleanText(draft?.outputMode)) {
      node.config.outputMode = cleanText(draft.outputMode);
    }
    if (isPlainConfigObject(draft?.outputSchema) && Object.keys(draft.outputSchema).length > 0) {
      node.config.outputSchema = draft.outputSchema;
    }
    if (isPlainConfigObject(draft?.writeState) && Object.keys(draft.writeState).length > 0) {
      const existing = isPlainConfigObject(node.config.writeState) ? node.config.writeState : {};
      node.config.writeState = { ...existing, ...draft.writeState };
    }
  }

  function isPlainConfigObject(value) {
    return value && typeof value === "object" && !Array.isArray(value);
  }

  function selectedVariablePickerLabel(inputPicker) {
    const selected = inputPicker.options?.[inputPicker.selectedIndex];
    return cleanText(selected?.textContent) || cleanText(inputPicker.value) || "工作流任务输入";
  }

  function inferLlmInputVariable(node, parts = {}) {
    if (isExactTemplateReference(parts.inputVariable)) return parts.inputVariable;
    const incomingNodes = state.edges
      .filter((edge) => edge.to === node.id)
      .map((edge) => state.nodes.find((candidate) => candidate.id === edge.from))
      .filter(Boolean);
    const startNode = incomingNodes.find((candidate) => candidate.type === "start");
    if (startNode) return "{{input.message}}";
    const llmNode = incomingNodes.find((candidate) => candidate.type === "llm");
    if (llmNode) return `{{nodes.${llmNode.id}.answer}}`;
    const retrieverNode = incomingNodes.find((candidate) => candidate.type === "retriever");
    if (retrieverNode) return "{{context}}";
    const firstUpstream = incomingNodes[0];
    if (firstUpstream) return `{{nodes.${firstUpstream.id}}}`;
    return "{{input.message}}";
  }

  function composeLlmPrompt(inputVariable, instruction) {
    const source = isExactTemplateReference(inputVariable) ? inputVariable.trim() : "{{input.message}}";
    const task = cleanText(instruction) || LLM_PROMPT_DEFAULT_INSTRUCTION;
    return `${task}\n\n输入内容：\n${source}\n\n请只根据上述输入内容执行任务。`;
  }

  function extractLlmPromptParts(prompt) {
    const text = String(prompt || "").trim();
    if (!text) return { inputVariable: "{{input.message}}", instruction: LLM_PROMPT_DEFAULT_INSTRUCTION };
    const sourceMatch = text.match(/(?:输入内容|用户输入|客户评论|Input)\s*[：:]\s*\n?\s*(\{\{[^{}]+}})/i);
    const fallbackMatch = text.match(/\{\{(?:input(?:\.[^{}]+)?|state\.[^{}]+|context|lastOutput(?:\.[^{}]+)?|nodes\.[^{}]+)}}/);
    const inputVariable = sourceMatch?.[1] || fallbackMatch?.[0] || "{{input.message}}";
    let instruction = text
      .replace(/(?:输入内容|用户输入|客户评论|Input)\s*[：:]\s*\n?\s*\{\{[^{}]+}}\s*/ig, "")
      .replace(/请只根据上述输入内容执行任务。/g, "")
      .trim();
    if (!instruction || /Answer\b.*workflow input/i.test(instruction)) {
      instruction = LLM_PROMPT_DEFAULT_INSTRUCTION;
    }
    return { inputVariable, instruction };
  }

  function renderLlmSettingsPanel({ node, schema, autoStructured }) {
    const { card, body } = nodePanelCard(
      "模型与提示词",
      "配置模型输入、模型选择与结构化输出；自动路由节点会隐藏系统托管的 JSON 字段。"
    );
    renderLlmPromptBuilder(body, node);
    appendPanelConfigField(body, node, schema, "model", {
      placeholder: "留空使用后端默认模型",
      hint: "只在需要覆盖默认模型时填写。"
    });
    if (!autoStructured) {
      appendPanelConfigField(body, node, schema, "outputMode");
      appendPanelConfigField(body, node, schema, "outputSchema", {
        field: { type: "object", defaultValue: {} }
      });
    }
    els.inspectorForm.appendChild(card);
  }

  function renderRetrieverSettingsPanel({ node, schema }) {
    const { card, body } = nodePanelCard(
      "检索设置",
      "当前后端由统一 RAG 服务执行检索，这里只配置查询模板和返回条数。"
    );
    appendPanelConfigField(body, node, schema, "query", {
      hint: "通常使用用户消息，也可以引用上游节点输出。"
    });
    appendPanelConfigField(body, node, schema, "topK", {
      field: { type: "integer", defaultValue: 3 },
      hint: "数值越大，上下文更完整，但模型输入也更长。"
    });
    els.inspectorForm.appendChild(card);
  }

  function renderTavilySearchSettingsPanel({ node, schema }) {
    const { card, body } = nodePanelCard(
      "Tavily 网络搜索",
      "执行真实网络搜索；API 密钥由设置页统一管理，不会写入工作流。"
    );
    appendPanelConfigField(body, node, schema, "query", {
      hint: "通常直接选择用户消息，也可以选择上游节点生成的搜索词。"
    });
    appendPanelConfigField(body, node, schema, "searchDepth", {
      hint: "基础模式速度较快；深度模式会使用更多搜索额度。"
    });
    appendPanelConfigField(body, node, schema, "topic");
    appendPanelConfigField(body, node, schema, "maxResults", {
      field: { type: "integer", defaultValue: 5, constraints: { min: 1, max: 20 } }
    });
    appendPanelConfigField(body, node, schema, "includeAnswer", {
      hint: "关闭时仅返回来源列表，后续可交给大模型节点总结。"
    });
    body.appendChild(renderTavilyTimeRangeField(node));
    body.appendChild(renderTavilyDomainField(node, "includeDomains", "限定网站", "例如：spring.io, github.com"));
    body.appendChild(renderTavilyDomainField(node, "excludeDomains", "排除网站", "例如：example.com"));

    const advanced = document.createElement("details");
    advanced.className = "inspector-advanced";
    const summary = document.createElement("summary");
    summary.textContent = "高级搜索选项";
    advanced.appendChild(summary);
    advanced.appendChild(renderSchemaConfigField(node,
      schemaConfigField(schema, "includeRawContent", { type: "boolean", defaultValue: false }), {
        hint: "网页原文可能显著增大运行输出，仅在下游确实需要时开启。"
      }));
    body.appendChild(advanced);
    els.inspectorForm.appendChild(card);
  }

  function renderTavilyTimeRangeField(node) {
    const shell = fieldShell("时间范围");
    const select = document.createElement("select");
    select.className = "select";
    appendOption(select, "", "不限");
    [["day", "最近一天"], ["week", "最近一周"], ["month", "最近一月"], ["year", "最近一年"]]
      .forEach(([value, label]) => appendOption(select, value, label));
    select.value = node.config?.timeRange || "";
    select.addEventListener("change", (event) => {
      event.stopPropagation();
      const nextValue = select.value || null;
      if ((node.config?.timeRange || null) === nextValue) return;
      recordWorkflowUndo("编辑 Tavily 时间范围");
      if (nextValue) node.config.timeRange = nextValue;
      else delete node.config.timeRange;
      renderRouteMap(); renderNodes(); renderEdges();
    });
    shell.appendChild(select);
    return shell;
  }

  function renderTavilyDomainField(node, fieldName, label, placeholder) {
    const shell = fieldShell(label);
    const input = document.createElement("input");
    input.className = "text-input";
    input.type = "text";
    input.placeholder = placeholder;
    input.value = Array.isArray(node.config?.[fieldName]) ? node.config[fieldName].join(", ") : "";
    input.addEventListener("change", (event) => {
      event.stopPropagation();
      const nextValue = input.value.split(/[，,\n]/)
        .map((item) => item.trim())
        .filter(Boolean);
      if (safeStringify(node.config?.[fieldName] || []) === safeStringify(nextValue)) return;
      recordWorkflowUndo("编辑 Tavily 网站范围");
      node.config[fieldName] = nextValue;
      renderRouteMap(); renderNodes(); renderEdges();
    });
    shell.appendChild(input);
    return shell;
  }

  async function loadHttpCredentialCatalog(force = false) {
    if (state.httpCredentialsLoaded && !force) return state.httpCredentials;
    try {
      const credentials = await requestJson(API.httpCredentials);
      state.httpCredentials = Array.isArray(credentials) ? credentials : [];
    } catch (error) {
      state.httpCredentials = [];
      if (force) toast(error.message, true);
    }
    state.httpCredentialsLoaded = true;
    return state.httpCredentials;
  }

  function renderHttpRequestSettingsPanel({ node, schema }) {
    if (!state.httpCredentialsLoaded) {
      void loadHttpCredentialCatalog().then(() => rerenderInspectorForNode(node.id));
    }
    const { card, body } = nodePanelCard(
      "HTTP 请求",
      "调用外部 HTTP(S) API；私网地址、明文密钥和不安全重定向会在运行前被拦截。"
    );

    const target = document.createElement("div");
    target.className = "http-target-row";
    target.appendChild(renderSchemaConfigField(node,
      schemaConfigField(schema, "method", { type: "string", defaultValue: "GET",
        constraints: { allowedValues: ["GET", "HEAD", "POST", "PUT", "PATCH", "DELETE"] } }), {
        label: "方法", rerenderOnChange: true
      }));
    target.appendChild(renderSchemaConfigField(node,
      schemaConfigField(schema, "url", { type: "string", defaultValue: "", constraints: { templateVariables: TEMPLATE_VARIABLES } }), {
        label: "URL", placeholder: "https://api.example.com/resource",
        hint: "可以从变量图插入路径参数；不要把 token 写在 URL 中。"
      }));
    body.appendChild(target);
    body.appendChild(renderCurlImporter(node));
    body.appendChild(renderHttpRowsEditor(node, "headers", "Headers", "Header"));
    body.appendChild(renderHttpRowsEditor(node, "params", "Params", "Query"));
    body.appendChild(renderHttpAuthorization(node));
    body.appendChild(renderHttpBody(node));

    ["idempotent", "continueOnError"].forEach((name) => {
      if ((schema.configFields || []).some((field) => field.name === name)) {
        body.appendChild(renderSchemaConfigField(node,
          schemaConfigField(schema, name, { type: "boolean", defaultValue: false }), {
            hint: name === "idempotent"
              ? "POST/PUT/PATCH/DELETE 需重试时必须显式开启。"
              : "仅传输错误在重试耗尽后转为结构化失败输出。"
          }));
      }
    });
    els.inspectorForm.appendChild(card);
  }

  function renderReportExportSettingsPanel({ node, schema }) {
    node.config = node.config || {};
    if (!String(node.config.content || "").trim()) {
      const suggestedContent = nearestUpstreamReportContent(node.id);
      if (suggestedContent) node.config.content = suggestedContent;
    }
    const { card, body } = nodePanelCard(
      "报告导出",
      "将上游 Markdown 报告生成可下载文件；打印使用受控 HTML 预览，不会自动弹出打印窗口。"
    );

    const contentField = fieldShell("报告内容");
    const contentWrap = document.createElement("div");
    contentWrap.className = "report-content-source";
    const picker = createVisualVariablePicker(node.config.content || "", { upstreamOnly: true, expectedType: "string" });
    const selected = document.createElement("input");
    selected.className = "text-input";
    selected.readOnly = true;
    selected.placeholder = "选择上游报告文本";
    selected.value = node.config.content || "";
    picker.addEventListener("change", () => {
      selected.value = picker.value;
      updateReportConfig(node, "选择报告内容", "content", picker.value, false);
    });
    contentWrap.append(picker, selected);
    contentField.append(contentWrap,
      configHint("仅展示当前节点可达上游的文本输出；新增节点时会优先选择最近的大模型回答。"));
    body.appendChild(contentField);

    body.appendChild(renderReportFormats(node));

    const fileSection = document.createElement("section");
    fileSection.className = "report-settings-section";
    const fileHeading = document.createElement("strong");
    fileHeading.textContent = "文件信息";
    fileSection.appendChild(fileHeading);
    ["fileName", "title", "author", "organization"].forEach((name) => {
      fileSection.appendChild(renderSchemaConfigField(node, schemaConfigField(schema, name, {
        type: "string", defaultValue: ["fileName", "title"].includes(name) ? "研究报告" : "",
        constraints: { templateVariables: TEMPLATE_VARIABLES }
      })));
    });
    body.appendChild(fileSection);

    const layoutSection = document.createElement("section");
    layoutSection.className = "report-settings-section";
    const layoutHeading = document.createElement("strong");
    layoutHeading.textContent = "主题与页面";
    layoutSection.appendChild(layoutHeading);
    [
      ["theme", "string", "business", ["business", "minimal", "academic"]],
      ["paperSize", "string", "A4", ["A4", "Letter"]],
      ["orientation", "string", "portrait", ["portrait", "landscape"]],
      ["includeToc", "boolean", true],
      ["includePageNumbers", "boolean", true],
      ["retentionDays", "integer", 30]
    ].forEach(([name, type, defaultValue, allowedValues]) => {
      const constraints = allowedValues ? { allowedValues }
        : name === "retentionDays" ? { min: 1, max: 365 } : {};
      layoutSection.appendChild(renderSchemaConfigField(node, schemaConfigField(schema, name, {
        type, defaultValue, constraints
      })));
    });
    layoutSection.appendChild(configHint("制品默认保留 30 天，可配置 1–365 天。"));
    body.appendChild(layoutSection);
    els.inspectorForm.appendChild(card);
  }

  function renderReportFormats(node) {
    const shell = fieldShell("导出格式");
    const grid = document.createElement("div");
    grid.className = "report-format-grid";
    const selectedFormats = new Set(Array.isArray(node.config.formats) ? node.config.formats : ["pdf"]);
    [["pdf", "PDF"], ["docx", "DOCX"], ["html", "HTML"], ["markdown", "Markdown"], ["txt", "TXT"]]
      .forEach(([value, label]) => {
        const option = document.createElement("label");
        option.className = "report-format-option";
        const checkbox = document.createElement("input");
        checkbox.type = "checkbox";
        checkbox.checked = selectedFormats.has(value);
        const text = document.createElement("span");
        text.textContent = label;
        checkbox.addEventListener("change", () => {
          const next = new Set(Array.isArray(node.config.formats) ? node.config.formats : []);
          if (checkbox.checked) next.add(value); else next.delete(value);
          updateReportConfig(node, "编辑报告格式", "formats", Array.from(next), false);
        });
        option.append(checkbox, text);
        grid.appendChild(option);
      });
    shell.append(grid, configHint("可选多种格式；至少选择一种后才能运行或发布。"));
    return shell;
  }

  function nearestUpstreamReportContent(nodeId) {
    const queue = state.edges.filter((edge) => edge.to === nodeId).map((edge) => edge.from);
    const visited = new Set();
    while (queue.length > 0) {
      const candidateId = queue.shift();
      if (!candidateId || visited.has(candidateId)) continue;
      visited.add(candidateId);
      const candidate = state.nodes.find((item) => item.id === candidateId);
      if (candidate?.type === "llm") return `{{nodes.${candidate.id}.answer}}`;
      state.edges.filter((edge) => edge.to === candidateId).forEach((edge) => queue.push(edge.from));
    }
    return "";
  }

  function updateReportConfig(node, label, fieldName, value, rerender) {
    if (safeStringify(node.config?.[fieldName]) === safeStringify(value)) return;
    recordWorkflowUndo(label);
    node.config = node.config || {};
    node.config[fieldName] = value;
    renderRouteMap(); renderNodes(); renderEdges();
    if (rerender) renderInspector();
  }

  function renderCustomSettingsPanel({ node, schema }) {
    node.config = node.config || {};
    const mode = node.config.mode === "template" ? "template" : "ai";
    const { card, body } = nodePanelCard(
      "自由节点",
      "组合上游变量完成自定义处理；不执行代码、不直接访问网络，缺少证据时会明确标记缺失。"
    );

    const modeShell = fieldShell("处理方式");
    const segmented = document.createElement("div");
    segmented.className = "seg custom-mode-segment";
    [["ai", "AI 处理"], ["template", "模板转换"]].forEach(([value, label]) => {
      const button = document.createElement("button");
      button.type = "button";
      button.className = `seg-btn${mode === value ? " active" : ""}`;
      button.textContent = label;
      button.addEventListener("click", () => {
        if (mode === value) return;
        updateCustomConfig(node, "切换自由节点模式", "mode", value, true);
      });
      segmented.appendChild(button);
    });
    modeShell.append(segmented, configHint(mode === "ai"
      ? "将命名输入交给模型，适合分类、提取、归纳和文案生成。"
      : "不调用模型，只按模板稳定拼装结果。"));
    body.appendChild(modeShell);
    body.appendChild(renderCustomInputs(node));

    if (mode === "ai") {
      body.appendChild(renderCustomInstruction(node));
      appendPanelConfigField(body, node, schema, "model", {
        label: "模型（可选）",
        placeholder: "留空使用默认模型",
        readValue: (control) => String(control.value || "").trim() || null
      });
      appendPanelConfigField(body, node, schema, "outputMode", {
        label: "输出模式",
        rerenderOnChange: true
      });
      if ((node.config.outputMode || "text") === "json") {
        body.appendChild(renderCustomOutputFields(node));
      }
    } else {
      body.appendChild(renderCustomTemplate(node));
    }
    body.appendChild(configHint("需要调用 API、访问凭据或产生副作用时，请连接 HTTP 请求或已注册工具节点。"));
    els.inspectorForm.appendChild(card);
  }

  function renderCustomInputs(node) {
    const section = document.createElement("section");
    section.className = "custom-settings-section";
    const head = document.createElement("div");
    head.className = "http-section-head";
    const title = document.createElement("strong");
    title.textContent = "命名输入";
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm btn-ghost";
    add.textContent = "+ 添加输入";
    add.addEventListener("click", () => {
      const inputs = normalizeCustomInputs(node.config?.inputs);
      let index = Object.keys(inputs).length + 1;
      while (Object.hasOwn(inputs, `input${index}`)) index += 1;
      updateCustomConfig(node, "添加自由节点输入", "inputs", { ...inputs, [`input${index}`]: "" }, true);
    });
    head.append(title, add);
    section.appendChild(head);
    const entries = Object.entries(normalizeCustomInputs(node.config?.inputs));
    if (entries.length === 0) {
      section.appendChild(configHint("添加上游数据并命名，例如 patentEvidence、companies。"));
    }
    entries.forEach(([name, value]) => section.appendChild(renderCustomInputRow(node, entries, name, value)));
    return section;
  }

  function renderCustomInputRow(node, entries, name, value) {
    const row = document.createElement("div");
    row.className = "custom-input-row";
    const key = document.createElement("input");
    key.className = "text-input";
    key.value = name;
    key.placeholder = "输入名";
    key.pattern = "[a-zA-Z][a-zA-Z0-9_-]{0,63}";
    const fixedValue = document.createElement("input");
    fixedValue.className = "text-input custom-input-value";
    fixedValue.value = valueToEditorText(value);
    fixedValue.placeholder = "选择变量或填写固定值";
    const picker = createVisualVariablePicker(value, { upstreamOnly: true, expectedType: "any" });
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "icon-btn-sm";
    remove.title = "删除输入";
    remove.textContent = "×";

    key.addEventListener("change", () => {
      const nextName = key.value.trim();
      if (!/^[a-zA-Z][a-zA-Z0-9_-]{0,63}$/.test(nextName)) {
        toast("输入名需以字母开头，且只能包含字母、数字、_ 或 -", true);
        key.value = name;
        return;
      }
      if (nextName !== name && entries.some(([existing]) => existing === nextName)) {
        toast("输入名不能重复", true);
        key.value = name;
        return;
      }
      const next = {};
      entries.forEach(([existing, existingValue]) => {
        next[existing === name ? nextName : existing] = existingValue;
      });
      updateCustomConfig(node, "重命名自由节点输入", "inputs", next, true);
    });
    fixedValue.addEventListener("change", () => {
      const next = normalizeCustomInputs(node.config?.inputs);
      next[name] = fixedValue.value;
      picker.value = fixedValue.value;
      updateCustomConfig(node, "编辑自由节点输入", "inputs", next, false);
    });
    picker.addEventListener("change", () => {
      const next = normalizeCustomInputs(node.config?.inputs);
      next[name] = picker.value;
      fixedValue.value = picker.value;
      updateCustomConfig(node, "选择自由节点变量", "inputs", next, false);
    });
    remove.addEventListener("click", () => {
      const next = normalizeCustomInputs(node.config?.inputs);
      delete next[name];
      updateCustomConfig(node, "删除自由节点输入", "inputs", next, true);
    });
    row.append(key, fixedValue, picker, remove);
    return row;
  }

  function renderCustomInstruction(node) {
    const shell = fieldShell("业务要求");
    const textarea = document.createElement("textarea");
    textarea.className = "code-input custom-instruction-input";
    textarea.rows = 6;
    textarea.value = node.config?.instruction || "";
    textarea.placeholder = "例：按公司归纳专利数量、技术方向和数据来源；没有明确证据时输出缺失原因，不得推测。";
    textarea.addEventListener("change", () => {
      updateCustomConfig(node, "编辑自由节点要求", "instruction", textarea.value.trim(), false);
    });
    shell.append(textarea, configHint("只需描述要完成的业务任务，命名输入会自动交给模型。"));
    return shell;
  }

  function renderCustomTemplate(node) {
    const shell = fieldShell("输出模板");
    const textarea = document.createElement("textarea");
    textarea.className = "code-input custom-template-input";
    textarea.rows = 6;
    textarea.value = valueToEditorText(node.config?.template || "");
    textarea.placeholder = "# 研究结果\n\n{{nodes.previous.answer}}";
    const picker = createVisualVariablePicker("", { upstreamOnly: true, expectedType: "any" });
    picker.addEventListener("change", () => {
      const start = textarea.selectionStart ?? textarea.value.length;
      const end = textarea.selectionEnd ?? start;
      textarea.value = `${textarea.value.slice(0, start)}${picker.value}${textarea.value.slice(end)}`;
      updateCustomConfig(node, "插入模板变量", "template", textarea.value, false);
    });
    textarea.addEventListener("change", () => {
      updateCustomConfig(node, "编辑自由节点模板", "template", textarea.value, false);
    });
    shell.append(textarea, picker, configHint("可插入任意可达上游变量；模板模式不调用 AI。"));
    return shell;
  }

  function renderCustomOutputFields(node) {
    const section = document.createElement("section");
    section.className = "custom-settings-section";
    const head = document.createElement("div");
    head.className = "http-section-head";
    const title = document.createElement("strong");
    title.textContent = "结构化输出字段";
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm btn-ghost";
    add.textContent = "+ 添加字段";
    add.addEventListener("click", () => {
      const fields = customOutputFields(node.config?.outputSchema);
      let index = fields.length + 1;
      while (fields.some((field) => field.name === `field${index}`)) index += 1;
      fields.push({ name: `field${index}`, title: `字段 ${index}`, type: "string", required: false });
      setCustomOutputFields(node, "添加自由节点输出字段", fields, true);
    });
    head.append(title, add);
    section.appendChild(head);
    const fields = customOutputFields(node.config?.outputSchema);
    if (fields.length === 0) section.appendChild(configHint("添加下游节点可直接选择的输出字段，无需编写 JSON Schema。"));
    fields.forEach((field, index) => section.appendChild(renderCustomOutputFieldRow(node, fields, field, index)));
    return section;
  }

  function renderCustomOutputFieldRow(node, fields, field, index) {
    const row = document.createElement("div");
    row.className = "custom-output-row";
    const name = document.createElement("input");
    name.className = "text-input";
    name.value = field.name;
    name.placeholder = "fieldName";
    const title = document.createElement("input");
    title.className = "text-input";
    title.value = field.title;
    title.placeholder = "显示名";
    const type = document.createElement("select");
    type.className = "select";
    [["string", "文本"], ["number", "数字"], ["integer", "整数"], ["boolean", "布尔"], ["object", "对象"], ["array", "列表"]]
      .forEach(([value, label]) => appendOption(type, value, label));
    type.value = field.type;
    const required = document.createElement("label");
    required.className = "custom-required-toggle";
    const checkbox = document.createElement("input");
    checkbox.type = "checkbox";
    checkbox.checked = field.required;
    const requiredText = document.createElement("span");
    requiredText.textContent = "必填";
    required.append(checkbox, requiredText);
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "icon-btn-sm";
    remove.title = "删除字段";
    remove.textContent = "×";

    const commit = (label, patch) => {
      const next = fields.map((item, candidateIndex) => candidateIndex === index ? { ...item, ...patch } : item);
      setCustomOutputFields(node, label, next, false);
    };
    name.addEventListener("change", () => {
      const value = name.value.trim();
      if (!/^[a-zA-Z][a-zA-Z0-9_-]{0,63}$/.test(value)
          || fields.some((candidate, candidateIndex) => candidateIndex !== index && candidate.name === value)) {
        toast("字段名需以字母开头且不能重复", true);
        name.value = field.name;
        return;
      }
      commit("编辑自由节点字段名", { name: value });
    });
    title.addEventListener("change", () => commit("编辑自由节点字段显示名", { title: title.value.trim() }));
    type.addEventListener("change", () => commit("编辑自由节点字段类型", { type: type.value }));
    checkbox.addEventListener("change", () => commit("编辑自由节点必填字段", { required: checkbox.checked }));
    remove.addEventListener("click", () => {
      setCustomOutputFields(node, "删除自由节点输出字段", fields.filter((_, candidateIndex) => candidateIndex !== index), true);
    });
    row.append(name, title, type, required, remove);
    return row;
  }

  function customOutputFields(outputSchema) {
    const schema = outputSchema && typeof outputSchema === "object" && !Array.isArray(outputSchema) ? outputSchema : {};
    const properties = schema.properties && typeof schema.properties === "object" && !Array.isArray(schema.properties)
      ? schema.properties : {};
    const required = new Set(Array.isArray(schema.required) ? schema.required.map(String) : []);
    return Object.entries(properties).map(([name, field]) => ({
      name,
      title: String(field?.title || ""),
      type: ["string", "number", "integer", "boolean", "object", "array"].includes(field?.type) ? field.type : "string",
      required: required.has(name)
    }));
  }

  function setCustomOutputFields(node, label, fields, rerender) {
    const properties = {};
    fields.forEach((field) => {
      properties[field.name] = { type: field.type, ...(field.title ? { title: field.title } : {}) };
    });
    const required = fields.filter((field) => field.required).map((field) => field.name);
    updateCustomConfig(node, label, "outputSchema", {
      type: "object", properties, ...(required.length ? { required } : {}), additionalProperties: false
    }, rerender);
  }

  function normalizeCustomInputs(value) {
    return value && typeof value === "object" && !Array.isArray(value) ? { ...value } : {};
  }

  function updateCustomConfig(node, label, fieldName, value, rerender) {
    if (safeStringify(node.config?.[fieldName]) === safeStringify(value)) return;
    recordWorkflowUndo(label);
    node.config = node.config || {};
    if ((fieldName === "model" || fieldName === "instruction") && !String(value || "").trim()) {
      delete node.config[fieldName];
    } else {
      node.config[fieldName] = value;
    }
    renderRouteMap(); renderNodes(); renderEdges();
    if (rerender) renderInspector();
  }

  function renderCurlImporter(node) {
    const details = document.createElement("details");
    details.className = "inspector-advanced http-curl-import";
    const summary = document.createElement("summary");
    summary.textContent = "导入 cURL";
    const textarea = document.createElement("textarea");
    textarea.className = "code-input";
    textarea.rows = 4;
    textarea.placeholder = "curl -X POST https://api.example.com/items -H 'Content-Type: application/json' -d '{\"name\":\"demo\"}'";
    const action = document.createElement("button");
    action.type = "button";
    action.className = "btn btn-sm btn-ghost";
    action.textContent = "导入请求";
    action.addEventListener("click", (event) => {
      event.preventDefault();
      const parsed = parseCurlRequest(textarea.value);
      if (!parsed.url) {
        toast("未从 cURL 中识别到 URL", true);
        return;
      }
      recordWorkflowUndo("导入 cURL");
      node.config = { ...node.config, ...parsed.config };
      renderInspector(); renderRouteMap(); renderNodes(); renderEdges();
      toast(parsed.secretHeadersSkipped
        ? "已导入；鉴权头未写入画布，请选择托管凭据"
        : "已导入 cURL 配置");
    });
    details.append(summary, textarea, action,
      configHint("鉴权头会被自动忽略，密钥必须在凭据中心配置。"));
    return details;
  }

  function parseCurlRequest(source) {
    const tokens = String(source || "").match(/(?:[^\s"']+|"[^"]*"|'[^']*')+/g)?.map((token) => {
      if ((token.startsWith("'") && token.endsWith("'")) || (token.startsWith('"') && token.endsWith('"'))) {
        return token.slice(1, -1);
      }
      return token;
    }) || [];
    const config = { method: "GET", headers: [], params: [], authorization: { type: "none" }, body: { type: "none", value: "" } };
    let data = null;
    let secretHeadersSkipped = false;
    for (let index = 0; index < tokens.length; index += 1) {
      const token = tokens[index];
      if (token === "curl") continue;
      if ((token === "-X" || token === "--request") && tokens[index + 1]) {
        config.method = tokens[++index].toUpperCase();
        continue;
      }
      if ((token === "-H" || token === "--header") && tokens[index + 1]) {
        const header = tokens[++index];
        const split = header.indexOf(":");
        if (split > 0) {
          const key = header.slice(0, split).trim();
          const value = header.slice(split + 1).trim();
          if (/authorization|api[-_]?key|token|secret|cookie/i.test(key)) secretHeadersSkipped = true;
          else config.headers.push({ key, value, enabled: true });
        }
        continue;
      }
      if (["-d", "--data", "--data-raw", "--data-binary"].includes(token) && tokens[index + 1]) {
        data = tokens[++index];
        if (config.method === "GET") config.method = "POST";
        continue;
      }
      if (/^https?:\/\//i.test(token)) config.url = token;
    }
    if (data != null) {
      try { config.body = { type: "json", value: JSON.parse(data) }; }
      catch (error) { config.body = { type: "raw", value: data, contentType: "text/plain; charset=UTF-8" }; }
    }
    return { url: config.url, config, secretHeadersSkipped };
  }

  function renderHttpRowsEditor(node, fieldName, titleText, keyPlaceholder) {
    const section = document.createElement("section");
    section.className = "http-kv-section";
    const head = document.createElement("div");
    head.className = "http-section-head";
    const title = document.createElement("strong");
    title.textContent = titleText;
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm btn-ghost";
    add.textContent = "+ 添加";
    add.addEventListener("click", () => {
      const rows = normalizeHttpRows(node.config?.[fieldName]);
      updateHttpConfig(node, `添加 ${titleText}`, fieldName, [...rows, { key: "", value: "", enabled: true }], true);
    });
    head.append(title, add);
    section.appendChild(head);
    const rows = normalizeHttpRows(node.config?.[fieldName]);
    if (rows.length === 0) section.appendChild(configHint(`暂无 ${titleText}，按需添加。`));
    rows.forEach((row, index) => section.appendChild(httpKeyValueRow({
      node, rows, index, fieldName, keyPlaceholder, expectedType: "any"
    })));
    return section;
  }

  function httpKeyValueRow({ node, rows, index, fieldName, keyPlaceholder, expectedType }) {
    const row = rows[index];
    const wrap = document.createElement("div");
    wrap.className = "http-kv-row";
    const enabled = document.createElement("input");
    enabled.type = "checkbox";
    enabled.checked = row.enabled !== false;
    enabled.title = "启用";
    const key = document.createElement("input");
    key.className = "text-input";
    key.placeholder = keyPlaceholder || "Key";
    key.value = row.key || "";
    const value = document.createElement("input");
    value.className = "text-input";
    value.placeholder = "Value";
    value.value = valueToEditorText(row.value);
    const picker = createVisualVariablePicker(row.value || "", { expectedType });
    const remove = document.createElement("button");
    remove.type = "button";
    remove.className = "icon-btn-sm";
    remove.title = "删除";
    remove.textContent = "×";

    const commit = (label, nextValue) => {
      const nextRows = cloneWorkflowValue(rows);
      nextRows[index] = { ...nextRows[index], ...nextValue };
      updateHttpConfig(node, label, fieldName, nextRows, false);
    };
    enabled.addEventListener("change", () => commit("启用请求字段", { enabled: enabled.checked }));
    key.addEventListener("change", () => commit("编辑请求字段", { key: key.value.trim() }));
    value.addEventListener("change", () => commit("编辑请求值", { value: value.value }));
    picker.addEventListener("change", () => {
      value.value = picker.value;
      commit("选择请求变量", { value: picker.value });
    });
    remove.addEventListener("click", () => {
      updateHttpConfig(node, "删除请求字段", fieldName,
        rows.filter((_, rowIndex) => rowIndex !== index), true);
    });
    wrap.append(enabled, key, value, picker, remove);
    return wrap;
  }

  function normalizeHttpRows(value) {
    return Array.isArray(value) ? value.map((row) => ({
      key: String(row?.key || ""), value: row?.value ?? "", enabled: row?.enabled !== false
    })) : [];
  }

  function updateHttpConfig(node, label, fieldName, value, rerender) {
    if (safeStringify(node.config?.[fieldName]) === safeStringify(value)) return;
    recordWorkflowUndo(label);
    node.config = node.config || {};
    node.config[fieldName] = value;
    renderRouteMap(); renderNodes(); renderEdges();
    if (rerender) renderInspector();
  }

  function renderHttpAuthorization(node) {
    const section = document.createElement("section");
    section.className = "http-kv-section";
    const title = document.createElement("strong");
    title.textContent = "Authorization";
    const authorization = node.config?.authorization || { type: "none" };
    const type = document.createElement("select");
    type.className = "select";
    appendOption(type, "none", "无鉴权");
    appendOption(type, "credential", "托管凭据");
    type.value = authorization.type || "none";
    type.addEventListener("change", () => updateHttpConfig(node, "切换 HTTP 鉴权", "authorization",
      type.value === "credential" ? { type: "credential", credentialId: "" } : { type: "none" }, true));
    section.append(title, type);
    if (type.value === "credential") {
      const credentialRow = document.createElement("div");
      credentialRow.className = "http-credential-row";
      const select = document.createElement("select");
      select.className = "select";
      appendOption(select, "", "选择凭据…");
      (state.httpCredentials || []).forEach((credential) => appendOption(select, credential.credentialId,
        `${credential.name} · ${httpCredentialTypeLabel(credential.type)}`));
      select.value = authorization.credentialId || "";
      select.addEventListener("change", () => updateHttpConfig(node, "选择 HTTP 凭据", "authorization",
        { type: "credential", credentialId: select.value }, false));
      const configure = document.createElement("button");
      configure.type = "button";
      configure.className = "btn btn-sm btn-ghost";
      configure.textContent = "配置凭据";
      configure.addEventListener("click", openHttpCredentialSettings);
      credentialRow.append(select, configure);
      section.append(credentialRow, configHint("画布只保存凭据 ID，不会显示或回填密钥。"));
    }
    return section;
  }

  function httpCredentialTypeLabel(type) {
    return ({ bearer: "Bearer", api_key_header: "API Key Header", basic: "Basic Auth" })[type] || type;
  }

  function openHttpCredentialSettings() {
    document.querySelector('[data-view="settings"]')?.click();
    setTimeout(() => document.getElementById("http-credential-name")?.focus(), 80);
  }

  function renderHttpBody(node) {
    const section = document.createElement("section");
    section.className = "http-kv-section";
    const title = document.createElement("strong");
    title.textContent = "Body";
    const body = node.config?.body && typeof node.config.body === "object"
      ? node.config.body : { type: "none", value: "" };
    const select = document.createElement("select");
    select.className = "select";
    [["none", "none"], ["json", "JSON"], ["raw", "raw"],
      ["x-www-form-urlencoded", "x-www-form-urlencoded"], ["form-data", "form-data（文本）"]]
      .forEach(([value, label]) => appendOption(select, value, label));
    select.value = body.type || "none";
    select.addEventListener("change", () => {
      const value = ["json"].includes(select.value) ? {}
        : ["x-www-form-urlencoded", "form-data"].includes(select.value) ? [] : "";
      updateHttpConfig(node, "切换 HTTP Body", "body", { type: select.value, value }, true);
    });
    section.append(title, select);
    if (select.value === "json") section.appendChild(renderHttpJsonBody(node, body));
    if (select.value === "raw") section.appendChild(renderHttpRawBody(node, body));
    if (["x-www-form-urlencoded", "form-data"].includes(select.value)) {
      section.appendChild(renderHttpBodyRows(node, body));
    }
    return section;
  }

  function renderHttpJsonBody(node, body) {
    const wrap = document.createElement("div");
    wrap.className = "http-json-fields";
    const value = body.value && typeof body.value === "object" && !Array.isArray(body.value) ? body.value : {};
    const rows = Object.entries(value).map(([key, item]) => ({ key, value: item, enabled: true }));
    rows.forEach((row, index) => {
      const rowElement = document.createElement("div");
      rowElement.className = "http-json-row";
      const key = document.createElement("input");
      key.className = "text-input";
      key.placeholder = "JSON 字段";
      key.value = row.key;
      const fieldValue = document.createElement("input");
      fieldValue.className = "text-input";
      fieldValue.placeholder = "Value";
      fieldValue.value = valueToEditorText(row.value);
      const picker = createVisualVariablePicker(row.value || "", { expectedType: "any" });
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "icon-btn-sm";
      remove.title = "删除";
      remove.textContent = "×";
      key.addEventListener("change", () => commitJsonRows(index, { key: key.value.trim() }, "编辑 JSON 字段"));
      fieldValue.addEventListener("change", () => commitJsonRows(index,
        { value: parseLooseHttpValue(fieldValue.value) }, "编辑 JSON Body"));
      picker.addEventListener("change", () => {
        fieldValue.value = picker.value;
        commitJsonRows(index, { value: picker.value }, "选择 JSON 变量");
      });
      remove.addEventListener("click", () => commitJsonRows(index, null, "删除 JSON 字段", true));
      rowElement.append(key, fieldValue, picker, remove);
      wrap.appendChild(rowElement);
    });
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm btn-ghost";
    add.textContent = "+ 添加 JSON 字段";
    add.addEventListener("click", () => {
      const next = { ...value, [`field${Object.keys(value).length + 1}`]: "" };
      updateHttpConfig(node, "添加 JSON 字段", "body", { ...body, type: "json", value: next }, true);
    });
    wrap.appendChild(add);

    function commitJsonRows(index, patch, label, rerender = false) {
      const nextRows = cloneWorkflowValue(rows);
      if (patch == null) nextRows.splice(index, 1);
      else nextRows[index] = { ...nextRows[index], ...patch };
      const next = {};
      nextRows.forEach((item) => {
        const itemKey = String(item.key || "").trim();
        if (itemKey) next[itemKey] = item.value;
      });
      updateHttpConfig(node, label, "body", { ...body, type: "json", value: next }, rerender);
    }
    return wrap;
  }

  function renderHttpRawBody(node, body) {
    const wrap = document.createElement("div");
    wrap.className = "http-raw-body";
    const textarea = document.createElement("textarea");
    textarea.className = "code-input";
    textarea.rows = 5;
    textarea.value = String(body.value || "");
    textarea.placeholder = "请求正文";
    const picker = createVisualVariablePicker("", { expectedType: "any" });
    textarea.addEventListener("change", () => updateHttpConfig(node, "编辑 Raw Body", "body",
      { ...body, type: "raw", value: textarea.value }, false));
    picker.addEventListener("change", () => {
      applyPickedVariable(textarea, picker.value, "insert");
      updateHttpConfig(node, "插入 Body 变量", "body", { ...body, type: "raw", value: textarea.value }, false);
    });
    wrap.append(textarea, picker);
    return wrap;
  }

  function renderHttpBodyRows(node, body) {
    const rows = normalizeHttpRows(body.value);
    const section = document.createElement("div");
    section.className = "http-json-fields";
    rows.forEach((row, index) => {
      const rowElement = document.createElement("div");
      rowElement.className = "http-json-row";
      const key = document.createElement("input");
      key.className = "text-input";
      key.placeholder = "字段";
      key.value = row.key;
      const fieldValue = document.createElement("input");
      fieldValue.className = "text-input";
      fieldValue.placeholder = "Value";
      fieldValue.value = valueToEditorText(row.value);
      const picker = createVisualVariablePicker(row.value || "", { expectedType: "any" });
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "icon-btn-sm";
      remove.title = "删除";
      remove.textContent = "×";
      key.addEventListener("change", () => commit(index, { key: key.value.trim() }, "编辑 Body 字段"));
      fieldValue.addEventListener("change", () => commit(index, { value: fieldValue.value }, "编辑 Body 值"));
      picker.addEventListener("change", () => {
        fieldValue.value = picker.value;
        commit(index, { value: picker.value }, "选择 Body 变量");
      });
      remove.addEventListener("click", () => commit(index, null, "删除 Body 字段", true));
      rowElement.append(key, fieldValue, picker, remove);
      section.appendChild(rowElement);
    });
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm btn-ghost";
    add.textContent = "+ 添加字段";
    add.addEventListener("click", () => updateHttpConfig(node, "添加 Body 字段", "body",
      { ...body, value: [...rows, { key: "", value: "", enabled: true }] }, true));
    section.appendChild(add);
    function commit(index, patch, label, rerender = false) {
      const nextRows = cloneWorkflowValue(rows);
      if (patch == null) nextRows.splice(index, 1);
      else nextRows[index] = { ...nextRows[index], ...patch };
      updateHttpConfig(node, label, "body", { ...body, value: nextRows }, rerender);
    }
    return section;
  }

  function parseLooseHttpValue(value) {
    const text = String(value || "");
    if (isExactTemplateReference(text)) return text;
    try { return JSON.parse(text); } catch (error) { return text; }
  }

  function renderToolSettingsPanel({ node, schema }) {
    if (!state.toolCatalogLoaded) {
      void loadToolCatalog().then(() => rerenderInspectorForNode(node.id));
    }
    const { card, body } = nodePanelCard(
      "工具调用",
      "选择 ToolGatewayService 中注册的工具，并按工具业务参数配置 arguments。"
    );
    const toolName = node.config.toolName || "getCurrentTime";
    const toolField = fieldShell("调用工具");
    const toolSelect = toolSelectControl(toolName);
    toolSelect.addEventListener("change", (event) => {
      event.stopPropagation();
      if (node.config.toolName === toolSelect.value) return;
      recordWorkflowUndo("编辑工具配置");
      node.config.toolName = toolSelect.value;
      node.config.arguments = defaultToolArguments(toolSelect.value);
      delete node.config.expression;
      renderInspector();
      renderRouteMap();
      renderNodes();
      renderEdges();
    });
    toolField.appendChild(toolSelect);
    const activeTool = toolCatalogItem(toolSelect.value);
    toolField.appendChild(toolSummary(activeTool));
    body.appendChild(toolField);
    body.appendChild(renderToolArgumentsPanel(node, schema, activeTool));
    const idempotentField = schemaConfigField(schema, "idempotent", { type: "boolean", defaultValue: false });
    if ((schema.configFields || []).some((field) => field.name === "idempotent")) {
      body.appendChild(renderSchemaConfigField(node, idempotentField, {
        hint: "开启后，运行控制中的重试次数才适合用于这个工具节点。"
      }));
    }
    els.inspectorForm.appendChild(card);
  }

  function renderVariableAggregatorSettingsPanel({ node }) {
    const { card, body } = nodePanelCard(
      "变量聚合器",
      "按顺序选择第一个已执行且字段存在的上游输出；空文本、空列表和空对象都是有效值。"
    );
    const modeField = fieldShell("模式");
    const mode = document.createElement("select");
    mode.className = "select";
    appendOption(mode, "single", "单一输出");
    appendOption(mode, "groups", "分组输出");
    mode.value = node.config?.mode === "groups" ? "groups" : "single";
    mode.addEventListener("change", () => {
      const next = mode.value === "groups"
        ? { ...node.config, mode: "groups", groups: normalizeAggregatorGroups(node.config?.groups) }
        : { ...node.config, mode: "single", outputType: node.config?.outputType || "string",
            variables: Array.isArray(node.config?.variables) ? node.config.variables : [] };
      commitAggregatorConfig(node, "切换聚合器模式", next, true);
    });
    modeField.appendChild(mode);
    body.appendChild(modeField);

    if (mode.value === "groups") renderAggregatorGroups(body, node);
    else renderSingleAggregator(body, node);
    body.appendChild(renderAggregatorOutputPreview(node));
    els.inspectorForm.appendChild(card);
  }

  function renderSingleAggregator(body, node) {
    const outputType = node.config?.outputType || "string";
    body.appendChild(aggregatorTypeField(outputType, (nextType) => {
      commitAggregatorConfig(node, "修改聚合输出类型", { ...node.config, outputType: nextType }, true);
    }));
    body.appendChild(renderAggregatorCandidates({
      node,
      values: Array.isArray(node.config?.variables) ? node.config.variables : [],
      outputType,
      onCommit: (values, label, rerender = true) => commitAggregatorConfig(node, label,
        { ...node.config, variables: values }, rerender)
    }));
  }

  function renderAggregatorGroups(body, node) {
    const groups = normalizeAggregatorGroups(node.config?.groups);
    const list = document.createElement("div");
    list.className = "aggregator-group-list";
    groups.forEach((group, groupIndex) => {
      const groupCard = document.createElement("section");
      groupCard.className = "aggregator-group-card";
      const head = document.createElement("div");
      head.className = "aggregator-group-head";
      const title = document.createElement("strong");
      title.textContent = group.label || group.key || `分组 ${groupIndex + 1}`;
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "icon-btn-sm";
      remove.title = "删除分组";
      remove.textContent = "×";
      remove.addEventListener("click", () => {
        const next = groups.filter((_, index) => index !== groupIndex);
        commitAggregatorConfig(node, "删除聚合分组", { ...node.config, groups: next }, true);
      });
      head.append(title, remove);
      groupCard.appendChild(head);

      const names = document.createElement("div");
      names.className = "aggregator-group-name-row";
      const keyField = fieldShell("分组 key");
      const keyInput = document.createElement("input");
      keyInput.className = "text-input";
      keyInput.value = group.key;
      keyInput.placeholder = "departmentResult";
      keyInput.addEventListener("change", () => updateAggregatorGroup(node, groups, groupIndex,
        { key: keyInput.value.trim() }, "编辑聚合分组", true));
      keyField.append(keyInput, configHint("以字母开头，仅使用字母、数字、_ 或 -。"));
      const labelField = fieldShell("显示名");
      const labelInput = document.createElement("input");
      labelInput.className = "text-input";
      labelInput.value = group.label;
      labelInput.placeholder = "部门处理结果";
      labelInput.addEventListener("change", () => updateAggregatorGroup(node, groups, groupIndex,
        { label: labelInput.value.trim() }, "编辑聚合分组", true));
      labelField.appendChild(labelInput);
      names.append(keyField, labelField);
      groupCard.appendChild(names);
      groupCard.appendChild(aggregatorTypeField(group.outputType, (nextType) => {
        updateAggregatorGroup(node, groups, groupIndex, { outputType: nextType }, "修改分组输出类型", true);
      }));
      groupCard.appendChild(renderAggregatorCandidates({
        node,
        values: group.variables,
        outputType: group.outputType,
        onCommit: (values, label, rerender = true) => updateAggregatorGroup(node, groups, groupIndex,
          { variables: values }, label, rerender)
      }));
      list.appendChild(groupCard);
    });
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm btn-ghost";
    add.textContent = "+ 添加分组";
    add.addEventListener("click", () => {
      const next = [...groups, { key: uniqueAggregatorGroupKey(groups), label: "", outputType: "string", variables: [] }];
      commitAggregatorConfig(node, "添加聚合分组", { ...node.config, groups: next }, true);
    });
    list.appendChild(add);
    body.appendChild(list);
  }

  function aggregatorTypeField(value, onChange) {
    const field = fieldShell("输出类型");
    const select = document.createElement("select");
    select.className = "select";
    [["string", "文本"], ["number", "数字"], ["boolean", "布尔"], ["object", "对象"], ["array", "列表"]]
      .forEach(([type, label]) => appendOption(select, type, label));
    select.value = value || "string";
    select.addEventListener("change", () => onChange(select.value));
    field.appendChild(select);
    return field;
  }

  function renderAggregatorCandidates({ values, outputType, onCommit }) {
    const section = document.createElement("section");
    section.className = "aggregator-candidates";
    const head = document.createElement("div");
    head.className = "http-section-head";
    const title = document.createElement("strong");
    title.textContent = "候选变量";
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-sm btn-ghost";
    add.textContent = "+ 添加";
    add.addEventListener("click", () => onCommit([...values, ""], "添加聚合候选"));
    head.append(title, add);
    section.appendChild(head);
    if (values.length === 0) section.appendChild(configHint("请添加当前节点可达的上游输出。"));
    values.forEach((value, index) => {
      const row = document.createElement("div");
      row.className = "aggregator-candidate-row";
      const order = document.createElement("span");
      order.className = "aggregator-order";
      order.textContent = String(index + 1);
      const picker = createVisualVariablePicker(value, { upstreamOnly: true, expectedType: outputType });
      picker.addEventListener("change", () => {
        const next = [...values];
        next[index] = picker.value;
        onCommit(next, "选择聚合变量", false);
      });
      const up = smallOrderButton("↑", "上移", index > 0, () => {
        const next = [...values];
        [next[index - 1], next[index]] = [next[index], next[index - 1]];
        onCommit(next, "调整聚合顺序");
      });
      const down = smallOrderButton("↓", "下移", index < values.length - 1, () => {
        const next = [...values];
        [next[index + 1], next[index]] = [next[index], next[index + 1]];
        onCommit(next, "调整聚合顺序");
      });
      const remove = smallOrderButton("×", "删除", true,
        () => onCommit(values.filter((_, itemIndex) => itemIndex !== index), "删除聚合候选"));
      row.append(order, picker, up, down, remove);
      section.appendChild(row);
    });
    return section;
  }

  function smallOrderButton(text, title, enabled, onClick) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "icon-btn-sm";
    button.textContent = text;
    button.title = title;
    button.disabled = !enabled;
    button.addEventListener("click", onClick);
    return button;
  }

  function normalizeAggregatorGroups(value) {
    return Array.isArray(value) ? value.map((group, index) => ({
      key: String(group?.key || `group${index + 1}`),
      label: String(group?.label || ""),
      outputType: String(group?.outputType || "string"),
      variables: Array.isArray(group?.variables) ? [...group.variables] : []
    })) : [];
  }

  function updateAggregatorGroup(node, groups, index, patch, label, rerender) {
    const next = cloneWorkflowValue(groups);
    next[index] = { ...next[index], ...patch };
    commitAggregatorConfig(node, label, { ...node.config, groups: next }, rerender);
  }

  function uniqueAggregatorGroupKey(groups) {
    let index = groups.length + 1;
    while (groups.some((group) => group.key === `group${index}`)) index += 1;
    return `group${index}`;
  }

  function commitAggregatorConfig(node, label, config, rerender) {
    if (safeStringify(node.config) === safeStringify(config)) return;
    recordWorkflowUndo(label);
    node.config = config;
    renderRouteMap(); renderNodes(); renderEdges();
    if (rerender) renderInspector();
  }

  function renderAggregatorOutputPreview(node) {
    const preview = document.createElement("div");
    preview.className = "aggregator-preview";
    const title = document.createElement("strong");
    title.textContent = "动态输出预览";
    preview.appendChild(title);
    const lines = node.config?.mode === "groups"
      ? normalizeAggregatorGroups(node.config?.groups).map((group) =>
          `{{nodes.${node.id}.${group.key}.output}} · ${optionLabel("outputType", group.outputType)}`)
      : [`{{nodes.${node.id}.output}} · ${optionLabel("outputType", node.config?.outputType || "string")}`];
    if (lines.length === 0) lines.push("添加分组后将在这里显示输出路径");
    lines.forEach((line) => {
      const code = document.createElement("code");
      code.textContent = line;
      preview.appendChild(code);
    });
    return preview;
  }

  function renderConditionSettingsPanel({ node }) {
    renderConditionNodeConfig(node);
  }

  function renderLoopSettingsPanel({ node, schema }) {
    const { card, body } = nodePanelCard(
      "循环设置",
      "循环节点按 body / exit 两个出口路由，条件成立时进入循环体，否则退出循环。"
    );
    appendPanelConfigField(body, node, schema, "maxIterations", {
      field: { type: "integer", defaultValue: 10 },
      hint: "用于保护运行时，避免无限循环。"
    });
    appendPanelConfigField(body, node, schema, "left", { hint: "左侧取值支持模板变量。" });
    appendPanelConfigField(body, node, schema, "operator");
    appendPanelConfigField(body, node, schema, "right", { hint: "右侧可以是固定值或模板变量。" });
    body.appendChild(configHint("连线出口请使用「循环体」和「退出循环」，后端校验会要求这两个分支可达。"));
    els.inspectorForm.appendChild(card);
  }

  function renderSubgraphSettingsPanel({ node }) {
    const { card, body } = nodePanelCard(
      "子工作流设置",
      "选择一个已保存工作流作为子图；版本为空时运行最新版本。"
    );
    const definitionField = fieldShell("子工作流");
    const definitionControl = subgraphDefinitionControl(node.config.definitionId || "");
    definitionControl.addEventListener("change", (event) => {
      event.stopPropagation();
      if (node.config.definitionId === definitionControl.value) return;
      recordWorkflowUndo("编辑子工作流");
      node.config.definitionId = definitionControl.value;
      node.config.version = null;
      renderInspector();
      renderRouteMap();
      renderNodes();
      renderEdges();
    });
    definitionField.appendChild(definitionControl);
    body.appendChild(definitionField);

    const versionField = fieldShell("子工作流版本");
    const versionControl = document.createElement("select");
    versionControl.className = "select";
    appendOption(versionControl, "", "最新");
    if (node.config.version != null && node.config.version !== "") {
      appendOption(versionControl, String(node.config.version), `v${node.config.version}`);
      versionControl.value = String(node.config.version);
    }
    void populateSubgraphVersionOptions(versionControl, node.config.definitionId, node.config.version);
    versionControl.addEventListener("change", (event) => {
      event.stopPropagation();
      const nextVersion = versionControl.value ? Number.parseInt(versionControl.value, 10) : null;
      if (node.config.version === nextVersion) return;
      recordWorkflowUndo("编辑子工作流版本");
      node.config.version = nextVersion;
      renderRouteMap();
      renderNodes();
      renderEdges();
    });
    versionField.appendChild(versionControl);
    body.appendChild(versionField);
    els.inspectorForm.appendChild(card);
  }

  function renderDynamicSettingsPanel({ node, schema }) {
    if (!state.toolCatalogLoaded) {
      void loadToolCatalog().then(() => rerenderInspectorForNode(node.id));
    }
    const { card, body } = nodePanelCard(
      "动态分配设置",
      "从模板解析出工具列表或工具参数对象，并按允许工具白名单顺序执行。"
    );
    appendPanelConfigField(body, node, schema, "itemsFrom", {
      hint: "必须解析为列表；列表项可以是工具名，也可以是包含 toolName 的对象。"
    });
    appendPanelConfigField(body, node, schema, "action", {
      field: { type: "string", defaultValue: "tool", constraints: { allowedValues: ["tool"] } },
      hint: "当前演示运行时仅支持 tool 动作。"
    });
    body.appendChild(renderDynamicAllowedTools(node));
    els.inspectorForm.appendChild(card);
  }

  function renderExecutionControls(node, schema, options = {}) {
    const fields = (schema.configFields || []).filter((field) => {
      if (!COMMON_EXECUTION_FIELDS.has(field.name)) return false;
      if (options.autoStructured && AUTO_STRUCTURED_HIDDEN_FIELDS.has(field.name)) return false;
      return true;
    });
    if (fields.length === 0) return null;
    const details = document.createElement("details");
    details.className = "inspector-advanced execution-controls";
    const summary = document.createElement("summary");
    summary.textContent = "运行控制 / 状态写入";
    details.appendChild(summary);
    fields.forEach((field) => {
      details.appendChild(renderSchemaConfigField(node, field, {
        hint: executionFieldHint(field.name)
      }));
    });
    return details;
  }

  function executionFieldHint(name) {
    if (name === "writeState") return "把节点输出中的字段写入 workflow state，供后续节点用 {{state.xxx}} 引用。";
    if (name === "retryCount") return "失败后最多重试次数；工具节点建议配合「可安全重试」使用。";
    if (name === "timeoutMs") return "单节点运行超时时间，单位毫秒。";
    return "";
  }

  async function loadToolCatalog() {
    if (state.toolCatalogLoaded) return state.toolCatalog;
    if (toolCatalogRequest) return toolCatalogRequest;
    toolCatalogRequest = requestJson(API.toolsCatalog || API.tools)
      .then((payload) => {
        state.toolCatalog = normalizeToolCatalog(payload);
        state.toolCatalogLoaded = true;
        return state.toolCatalog;
      })
      .catch((error) => {
        state.toolCatalog = [];
        state.toolCatalogLoaded = true;
        toast(`工具目录不可用：${error.message}`, true);
        return state.toolCatalog;
      })
      .finally(() => { toolCatalogRequest = null; });
    return toolCatalogRequest;
  }

  function normalizeToolCatalog(payload) {
    const rawItems = Array.isArray(payload) ? payload : Array.isArray(payload?.tools) ? payload.tools : [];
    return rawItems
      .filter((tool) => tool && typeof tool === "object" && tool.name)
      .map((tool) => ({
        name: String(tool.name),
        label: tool.label || LOCAL_TOOL_ARGUMENT_PRESETS[tool.name]?.label || tool.name,
        provider: tool.provider || (tool.remote ? "mcp" : "local"),
        description: tool.description || LOCAL_TOOL_ARGUMENT_PRESETS[tool.name]?.description || "",
        executable: tool.executable !== false,
        remote: Boolean(tool.remote),
        inputSchema: tool.inputSchema || tool.schema || null
      }));
  }

  function toolCatalogItems() {
    const byName = new Map();
    Object.entries(LOCAL_TOOL_ARGUMENT_PRESETS).forEach(([name, preset]) => {
      byName.set(name, {
        name,
        label: preset.label || name,
        provider: "local",
        description: preset.description || "",
        executable: true,
        remote: false,
        inputSchema: null
      });
    });
    (state.toolCatalog || []).forEach((tool) => byName.set(tool.name, { ...(byName.get(tool.name) || {}), ...tool }));
    return Array.from(byName.values()).sort((a, b) => a.name.localeCompare(b.name));
  }

  function toolCatalogItem(name) {
    return toolCatalogItems().find((tool) => tool.name === name) || {
      name,
      label: name,
      provider: "custom",
      description: "未在当前工具目录中找到，保存后仍会交给后端按 toolName 解析。",
      executable: false,
      remote: false,
      inputSchema: null
    };
  }

  function toolSelectControl(value) {
    const select = document.createElement("select");
    select.className = "select";
    const items = toolCatalogItems();
    items.forEach((tool) => appendOption(select, tool.name, tool.label ? `${tool.label}（${tool.name}）` : tool.name));
    if (value && !items.some((tool) => tool.name === value)) appendOption(select, value, value);
    select.value = value || "getCurrentTime";
    return select;
  }

  function toolSummary(tool) {
    const summary = document.createElement("div");
    summary.className = "tool-summary-card";
    const meta = document.createElement("div");
    meta.className = "tool-summary-meta";
    meta.textContent = `${tool.provider || "local"} · ${tool.remote ? "远程" : "本地"} · ${tool.executable === false ? "不可执行" : "可执行"}`;
    const description = document.createElement("div");
    description.className = "config-hint";
    description.textContent = tool.description || "该工具未提供描述。";
    summary.append(meta, description);
    return summary;
  }

  function renderToolArgumentsPanel(node, schema, tool) {
    const wrap = document.createElement("div");
    wrap.className = "tool-arguments-panel";
    const title = document.createElement("div");
    title.className = "tool-arguments-title";
    title.textContent = "参数";
    wrap.appendChild(title);

    const schemaArgs = inputSchemaArguments(tool.inputSchema);
    if (schemaArgs.length > 0) {
      schemaArgs.forEach((arg) => wrap.appendChild(toolArgumentInput(node, arg.key, arg.label, arg)));
      wrap.appendChild(renderRawToolArgumentsDetails(node, schema));
      return wrap;
    }

    const preset = LOCAL_TOOL_ARGUMENT_PRESETS[tool.name];
    if (preset?.args?.length) {
      preset.args.forEach((arg) => wrap.appendChild(toolArgumentInput(node, arg.key, arg.label, arg)));
      wrap.appendChild(configHint("这些字段会保存到 config.arguments，运行时支持模板变量解析。"));
      return wrap;
    }

    if (tool.name === "getCurrentTime") {
      const empty = document.createElement("div");
      empty.className = "tool-empty-args";
      empty.textContent = "当前时间工具无需参数。";
      wrap.appendChild(empty);
      return wrap;
    }

    wrap.appendChild(renderSchemaConfigField(node, schemaConfigField(schema, "arguments", TOOL_ARGUMENT_FIELD), {
      label: "原始参数 JSON",
      hint: "远程或自定义工具可直接填写参数对象。"
    }));
    return wrap;
  }

  function toolArgumentInput(node, key, label, arg = {}) {
    const field = fieldShell(label || key);
    const args = normalizeObjectConfig(node.config.arguments);
    const input = textControl(args[key] ?? arg.defaultValue ?? "");
    input.placeholder = arg.placeholder || "";
    input.addEventListener("change", (event) => {
      event.stopPropagation();
      setToolArgument(node, key, readSchemaArgumentValue(input.value, arg.type));
    });
    field.appendChild(input);
    if (arg.description) field.appendChild(configHint(arg.description));
    return field;
  }

  function setToolArgument(node, key, value) {
    const args = normalizeObjectConfig(node.config.arguments);
    if (safeStringify(args[key]) === safeStringify(value)) return;
    recordWorkflowUndo("编辑工具参数");
    if (value === "" || value === undefined || value === null) delete args[key];
    else args[key] = value;
    node.config.arguments = args;
    renderRouteMap();
    renderNodes();
    renderEdges();
  }

  function defaultToolArguments(toolName) {
    if (toolName === "calculate") return { expression: "" };
    if (toolName === "queryOrderAPI") return { user_query: "{{input.message}}" };
    return {};
  }

  function renderRawToolArgumentsDetails(node, schema) {
    const details = document.createElement("details");
    details.className = "condition-advanced-details";
    const summary = document.createElement("summary");
    summary.textContent = "高级（原始参数 JSON）";
    details.appendChild(summary);
    details.appendChild(renderSchemaConfigField(node, schemaConfigField(schema, "arguments", TOOL_ARGUMENT_FIELD), {
      label: "原始参数 JSON"
    }));
    return details;
  }

  function inputSchemaArguments(inputSchema) {
    const schema = parseObjectLike(inputSchema);
    const properties = schema?.properties && typeof schema.properties === "object" && !Array.isArray(schema.properties)
      ? schema.properties
      : {};
    const required = new Set(Array.isArray(schema?.required) ? schema.required : []);
    return Object.keys(properties).map((key) => {
      const prop = properties[key] && typeof properties[key] === "object" ? properties[key] : {};
      return {
        key,
        label: prop.title || `${key}${required.has(key) ? " *" : ""}`,
        type: prop.type || "string",
        description: prop.description || "",
        defaultValue: prop.default ?? "",
        placeholder: prop.examples?.[0] != null ? String(prop.examples[0]) : ""
      };
    });
  }

  function readSchemaArgumentValue(value, type) {
    if (type === "integer") return Number.parseInt(value || "0", 10);
    if (type === "number") return Number(value || 0);
    if (type === "boolean") return String(value).toLowerCase() === "true";
    if (type === "object" || type === "array") return parseJsonInput(value, type === "array" ? [] : {});
    return value;
  }

  function renderDynamicAllowedTools(node) {
    const field = fieldShell("允许工具");
    const selected = normalizeStringList(node.config.allowedTools);
    const checkList = document.createElement("div");
    checkList.className = "tool-check-list";
    toolCatalogItems().forEach((tool) => {
      const label = document.createElement("label");
      label.className = "tool-check";
      const checkbox = document.createElement("input");
      checkbox.type = "checkbox";
      checkbox.value = tool.name;
      checkbox.checked = selected.includes(tool.name);
      checkbox.addEventListener("change", () => {
        const nextTools = checkedToolValues(checkList, extraInput.value);
        if (safeStringify(node.config.allowedTools) === safeStringify(nextTools)) return;
        recordWorkflowUndo("编辑动态工具白名单");
        node.config.allowedTools = nextTools;
        renderRouteMap();
        renderNodes();
        renderEdges();
      });
      const text = document.createElement("span");
      text.textContent = tool.label ? `${tool.label}（${tool.name}）` : tool.name;
      label.append(checkbox, text);
      checkList.appendChild(label);
    });
    field.appendChild(checkList);

    const knownNames = new Set(toolCatalogItems().map((tool) => tool.name));
    const extraInput = textControl(selected.filter((name) => !knownNames.has(name)).join(", "));
    extraInput.placeholder = "额外工具名，多个用逗号分隔";
    extraInput.addEventListener("change", () => {
      const nextTools = checkedToolValues(checkList, extraInput.value);
      if (safeStringify(node.config.allowedTools) === safeStringify(nextTools)) return;
      recordWorkflowUndo("编辑动态工具白名单");
      node.config.allowedTools = nextTools;
      renderRouteMap();
      renderNodes();
      renderEdges();
    });
    field.appendChild(extraInput);
    field.appendChild(configHint("动态节点运行前会校验白名单；不在这里的 toolName 会被拒绝。"));
    return field;
  }

  function checkedToolValues(checkList, extraText) {
    const values = Array.from(checkList.querySelectorAll("input[type='checkbox']:checked")).map((input) => input.value);
    return Array.from(new Set([...values, ...normalizeStringList(extraText)]));
  }

  function normalizeObjectConfig(value) {
    const parsed = parseObjectLike(value);
    return parsed && typeof parsed === "object" && !Array.isArray(parsed) ? { ...parsed } : {};
  }

  function parseObjectLike(value) {
    if (!value) return {};
    if (typeof value === "object" && !Array.isArray(value)) return value;
    if (typeof value === "string") {
      try { return JSON.parse(value || "{}"); } catch (error) { return {}; }
    }
    return {};
  }

  function normalizeStringList(value) {
    if (Array.isArray(value)) return value.map((item) => String(item || "").trim()).filter(Boolean);
    if (typeof value === "string") {
      const text = value.trim();
      if (!text) return [];
      try {
        const parsed = JSON.parse(text);
        if (Array.isArray(parsed)) return normalizeStringList(parsed);
      } catch (error) {
        // Fall through to comma-separated parsing.
      }
      return text.split(",").map((item) => item.trim()).filter(Boolean);
    }
    return [];
  }

  function rerenderInspectorForNode(nodeId) {
    const selected = findSelectedNode();
    if (selected?.id === nodeId) renderInspector();
  }

  function isAutoStructuredOutputNode(node) {
    return inferAutoStructuredOutputProfile(node) !== null;
  }

  function inferAutoStructuredOutputProfile(node) {
    if (!node || node.type !== "llm") return null;
    const config = node.config || {};
    if (config.autoStructuredOutputContract === CUSTOMER_SERVICE_INTENT_CONTRACT) {
      return CUSTOMER_SERVICE_INTENT_CONTRACT;
    }
    const schemaFields = outputSchemaFieldNames(config.outputSchema);
    const matchedFields = CUSTOMER_SERVICE_SCHEMA_FIELDS.filter((field) => schemaFields.has(field));
    if (schemaFields.has("intent") && schemaFields.has("confidence") && matchedFields.length >= 4) {
      return CUSTOMER_SERVICE_INTENT_CONTRACT;
    }
    const intentValues = inspectorConditionValuesForNodeField(node.id, "intent");
    if (intentValues.some((value) => CUSTOMER_SERVICE_INTENTS.includes(value))) {
      return CUSTOMER_SERVICE_INTENT_CONTRACT;
    }
    const text = [node.id, node.label, config.prompt]
      .map((value) => String(value || "").toLowerCase())
      .join(" ");
    const router = ["意图", "intent", "路由", "分流", "分类", "判断", "route", "classif"]
      .some((word) => text.includes(word));
    const service = ["客服", "订单", "商品", "政策", "物流", "退款", "退货", "customer", "order", "product", "policy"]
      .some((word) => text.includes(word));
    return router && service ? CUSTOMER_SERVICE_INTENT_CONTRACT : null;
  }

  function renderAutoStructuredOutputNotice(node) {
    const notice = document.createElement("div");
    notice.className = "auto-structured-output-summary";
    const title = document.createElement("div");
    title.className = "auto-structured-output-title";
    title.textContent = "结构化输出已自动配置";
    const fields = document.createElement("div");
    fields.className = "auto-structured-output-fields";
    ["意图", "是否已有订单号", "是否需要补充订单号", "订单号列表", "置信度"]
      .forEach((label) => {
        const chip = document.createElement("span");
        chip.textContent = label;
        fields.appendChild(chip);
      });
    const hint = document.createElement("div");
    hint.className = "config-hint";
    hint.textContent = "系统会根据节点语义和下游分支维护 JSON Schema 与变量写入，无需手动编辑字段。";
    notice.append(title, fields, hint);
    return notice;
  }

  function outputSchemaFieldNames(schema) {
    let parsed = schema;
    if (typeof parsed === "string") {
      try { parsed = JSON.parse(parsed || "{}"); } catch (error) { parsed = {}; }
    }
    const properties = parsed && typeof parsed === "object" && !Array.isArray(parsed)
      && parsed.properties && typeof parsed.properties === "object" && !Array.isArray(parsed.properties)
      ? parsed.properties
      : {};
    return new Set(Object.keys(properties));
  }

  function inspectorConditionValuesForNodeField(nodeId, fieldName) {
    const values = new Set();
    const marker = `{{nodes.${nodeId}.parsed.${fieldName}}}`;
    const inspect = (value) => {
      if (!value) return;
      if (Array.isArray(value)) { value.forEach(inspect); return; }
      if (typeof value !== "object") return;
      if (String(value.left || "").replace(/\s+/g, "") === marker) {
        const right = String(value.right || "").trim();
        if (right) values.add(right);
      }
      Object.values(value).forEach(inspect);
    };
    state.nodes.filter((candidate) => candidate.type === "condition").forEach((candidate) => inspect(candidate.config));
    return Array.from(values);
  }

  // 高级设置折叠区：所属流程 / 技术 ID / 节点类型（低频配置不占主视区）
  function renderAdvancedNodeSettings(node) {
    const details = document.createElement("details");
    details.className = "inspector-advanced";
    const summary = document.createElement("summary");
    summary.textContent = "高级设置（流程分组 / 技术 ID）";
    details.appendChild(summary);

    const routeField = fieldShell("所属流程");
    const routeInput = textControl(node.route || "");
    routeInput.placeholder = "例如：退货流程";
    routeInput.addEventListener("change", () => {
      if ((node.route || "") === cleanText(routeInput.value)) return;
      recordWorkflowUndo("编辑流程分组");
      node.route = cleanText(routeInput.value);
      renderRouteMap(); renderNodes(); renderEdges();
    });
    routeField.appendChild(routeInput);
    details.appendChild(routeField);

    const idField = fieldShell("技术 ID（高级）");
    const idInput = textControl(node.id);
    idInput.addEventListener("change", () => renameNode(node.id, idInput.value.trim()));
    idField.appendChild(idInput);
    const idHint = document.createElement("div");
    idHint.className = "config-hint";
    idHint.textContent = "用于模板变量和连线；改名后请检查 {{nodes.xxx}} 引用。";
    idField.appendChild(idHint);
    details.appendChild(idField);

    const typeField = fieldShell("节点类型");
    const typeValue = document.createElement("input");
    typeValue.className = "text-input";
    typeValue.value = `${nodeLabel(node.type)}（${node.type}）`;
    typeValue.disabled = true;
    typeField.appendChild(typeValue);
    details.appendChild(typeField);

    return details;
  }

  function renderConditionNodeConfig(node) {
    const updatePreview = () => {
      renderRouteMap();
      renderNodes();
      renderEdges();
    };
    const refreshPanel = () => {
      updatePreview();
      renderInspector();
    };
    els.inspectorForm.appendChild(conditionRuleControl(node.config, updatePreview, refreshPanel, () => recordWorkflowUndo("编辑条件规则")));
  }

  function renderConditionBranchEditor(node) {
    const card = document.createElement("section");
    card.className = "condition-if-else-card";
    const title = document.createElement("div");
    title.className = "condition-if-else-title";
    title.textContent = "下一步";
    const hint = document.createElement("div");
    hint.className = "condition-if-else-hint";
    hint.textContent = "条件节点按 IF / ELSE 两条出口执行；可直接在这里配置每个分支的下一个节点。";
    card.append(title, hint);

    const outgoing = state.edges.filter((edge) => edge.from === node.id);
    const branches = nodeBranches(node);
    const ifBranch = branches.find((branch) => branch.value === "true") || { value: "true", tag: "IF", desc: "满足" };
    const elseBranch = branches.find((branch) => branch.value === "false") || { value: "false", tag: "ELSE", desc: "不满足" };

    card.appendChild(renderConditionBranchNextSteps(node, ifBranch, outgoing, "IF 条件",
      "当上方规则判断为满足时执行。"));
    card.appendChild(renderConditionBranchNextSteps(node, elseBranch, outgoing, "ELSE",
      "用于定义当 IF 条件不满足时应执行的逻辑。"));

    const incoming = renderIncomingSources(node);
    if (incoming) card.appendChild(incoming);
    card.appendChild(renderAdvancedEdgeEditor(node));
    return card;
  }

  function renderConditionBranchNextSteps(node, branch, outgoing, heading, description) {
    const section = document.createElement("section");
    section.className = "condition-branch-section";
    const head = document.createElement("div");
    head.className = "condition-branch-head";
    const label = document.createElement("div");
    label.className = "condition-branch-label";
    label.textContent = heading;
    const desc = document.createElement("div");
    desc.className = "condition-branch-desc";
    desc.textContent = description;
    head.append(label, desc);
    section.appendChild(head);

    const next = renderNextStepGroup(node, branch, outgoing, true);
    next.classList.add("condition-branch-next");
    section.appendChild(next);
    return section;
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

  // 下一步（连线）编辑：按分支分组展示去向，技术细节折叠到高级编辑
  function renderEdgeEditor(node) {
    els.edgeList.innerHTML = "";
    els.addEdge?.classList.add("hidden");
    const outgoing = state.edges.filter((edge) => edge.from === node.id);
    const branches = nodeBranches(node);

    if (node.type !== "end") {
      const groups = branches.length > 0 ? branches : [{ value: "", tag: "出口", desc: "" }];
      groups.forEach((group) => {
        els.edgeList.appendChild(renderNextStepGroup(node, group, outgoing, branches.length > 0));
      });
    }

    const incoming = renderIncomingSources(node);
    if (incoming) els.edgeList.appendChild(incoming);

    els.edgeList.appendChild(renderAdvancedEdgeEditor(node));
  }

  function renderIncomingSources(node) {
    const incoming = state.edges.filter((edge) => edge.to === node.id);
    if (incoming.length === 0) return null;
    const line = document.createElement("div");
    line.className = "incoming-line";
    const label = document.createElement("span");
    label.className = "incoming-label";
    label.textContent = "来源";
    line.appendChild(label);
    incoming.forEach((edge) => {
      const source = state.nodes.find((n) => n.id === edge.from);
      const chip = document.createElement("button");
      chip.type = "button";
      chip.className = "incoming-chip";
      const branchText = edgeDisplayName({ label: "", condition: edge.condition });
      chip.textContent = source
        ? `${nodeDisplayName(source)}${branchText ? ` · ${branchText}` : ""}`
        : edge.from;
      chip.title = "点击选中来源节点";
      chip.addEventListener("click", () => selectOrConnectNode(edge.from));
      line.appendChild(chip);
    });
    return line;
  }

  function renderNextStepGroup(node, group, outgoing, grouped) {
    const wrap = document.createElement("div");
    wrap.className = "next-step-group";
    const related = outgoing.filter((edge) => (grouped ? (edge.condition || "") === group.value : true));

    const head = document.createElement("div");
    head.className = "next-step-head";
    const tag = document.createElement("span");
    tag.className = "branch-tag";
    tag.textContent = group.tag;
    const add = document.createElement("button");
    add.type = "button";
    add.className = "btn btn-ghost btn-sm next-step-add";
    add.textContent = "+ 添加下一步";
    add.addEventListener("click", (event) => {
      event.stopPropagation();
      openBlockSelector(node, group.value, add.getBoundingClientRect());
    });
    head.append(tag, add);
    wrap.appendChild(head);

    if (related.length === 0) {
      const empty = document.createElement("div");
      empty.className = "next-step-empty";
      empty.textContent = "未连接 · 点右上「+ 添加下一步」选择去向";
      wrap.appendChild(empty);
      return wrap;
    }

    related.forEach((edge) => {
      const row = document.createElement("div");
      row.className = "next-step-row";
      const arrow = document.createElement("span");
      arrow.className = "edge-arrow";
      arrow.textContent = "→";
      const target = document.createElement("select");
      target.className = "select";
      state.nodes
        .filter((n) => n.id !== node.id)
        .forEach((n) => appendOption(target, n.id, nodeDisplayName(n), `${nodeDisplayName(n)}（${n.id}）`));
      target.value = edge.to;
      target.addEventListener("change", () => {
        if (edge.to === target.value) return;
        recordWorkflowUndo("编辑下一步");
        edge.to = target.value;
        refreshAfterEdgeChange();
      });
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "icon-btn-sm next-step-remove";
      remove.title = "删除这条连线";
      remove.textContent = "×";
      remove.addEventListener("click", () => {
        const index = state.edges.indexOf(edge);
        if (index >= 0) recordWorkflowUndo("删除连线");
        if (index >= 0) state.edges.splice(index, 1);
        refreshAfterEdgeChange();
      });
      row.append(arrow, target, remove);
      wrap.appendChild(row);
    });
    return wrap;
  }

  function refreshAfterEdgeChange() {
    renderInspector();
    renderRouteMap();
    renderNodes();
    renderEdges();
  }

  // 高级连线编辑：保留完整 from/to/条件/备注/流程 表单，默认折叠
  function renderAdvancedEdgeEditor(node) {
    const details = document.createElement("details");
    details.className = "inspector-advanced edge-advanced";
    const summary = document.createElement("summary");
    summary.textContent = "高级连线编辑（条件 / 备注 / 流程分组）";
    details.appendChild(summary);
    const related = state.edges
      .map((edge, index) => ({ edge, index }))
      .filter(({ edge }) => edge.from === node.id || edge.to === node.id);
    if (related.length === 0) {
      const empty = document.createElement("div");
      empty.className = "empty-state";
      empty.textContent = "该节点暂无连线";
      details.appendChild(empty);
      return details;
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
      const labelInput = textControl(edge.label || "");
      labelInput.classList.add("edge-condition");
      labelInput.placeholder = "连线显示名（如：是退货）";
      const routeInput = textControl(edge.route || "");
      routeInput.classList.add("edge-condition");
      routeInput.placeholder = "所属流程（如：退货流程）";
      const remove = document.createElement("button");
      remove.type = "button"; remove.className = "edge-remove"; remove.textContent = "删除连线";
      from.addEventListener("change", () => {
        if (edge.from === from.value) return;
        recordWorkflowUndo("编辑连线来源");
        edge.from = from.value;
        refreshAfterEdgeChange();
      });
      to.addEventListener("change", () => {
        if (edge.to === to.value) return;
        recordWorkflowUndo("编辑连线去向");
        edge.to = to.value;
        refreshAfterEdgeChange();
      });
      condition.addEventListener("change", () => {
        const nextCondition = condition.value.trim();
        if ((edge.condition || "") === nextCondition) return;
        recordWorkflowUndo("编辑连线条件");
        edge.condition = nextCondition;
        renderRouteMap(); renderNodes(); renderEdges();
      });
      labelInput.addEventListener("change", () => {
        const nextLabel = cleanText(labelInput.value);
        if ((edge.label || "") === nextLabel) return;
        recordWorkflowUndo("编辑连线备注");
        edge.label = nextLabel;
        renderRouteMap(); renderNodes(); renderEdges(); renderInspector();
      });
      routeInput.addEventListener("change", () => {
        const nextRoute = cleanText(routeInput.value);
        if ((edge.route || "") === nextRoute) return;
        recordWorkflowUndo("编辑连线流程");
        edge.route = nextRoute;
        renderRouteMap(); renderNodes(); renderEdges(); renderInspector();
      });
      remove.addEventListener("click", () => {
        recordWorkflowUndo("删除连线");
        state.edges.splice(index, 1);
        refreshAfterEdgeChange();
      });
      row.append(from, arrow, to, condition, labelInput, routeInput, remove);
      details.appendChild(row);
    });
    return details;
  }

  function addEdgeFromSelected() {
    const node = findSelectedNode();
    if (!node) return;
    openBlockSelector(node, "", els.addEdge?.getBoundingClientRect() || els.edgeList.getBoundingClientRect());
  }

  function edgeConditionControl(fromNode, value) {
    if (fromNode?.type === "loop") {
      const select = document.createElement("select"); select.className = "select";
      appendOption(select, "body", "循环体"); appendOption(select, "exit", "退出循环"); appendOption(select, "", "不设置");
      select.value = value || ""; return select;
    }
    if (fromNode?.type === "condition") {
      const select = document.createElement("select"); select.className = "select";
      appendOption(select, "true", "满足"); appendOption(select, "false", "不满足"); appendOption(select, "", "不设置");
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
    recordWorkflowUndo("插入循环模板");
    ensureStartEndNodes();
    const loopId = uniqueNodeId("loop");
    const bodyId = uniqueNodeId("body_tool");
    const loopBackId = uniqueNodeId("loop_back");
    const afterId = uniqueNodeId("after_tool");
    const loopSchema = schemaForType("loop");
    const toolSchema = schemaForType("tool");
    state.nodes.push(
      { id: loopId, type: "loop", label: "循环判断", route: "循环流程", config: defaultConfig(loopSchema) },
      { id: bodyId, type: "tool", label: "循环体工具", route: "循环流程", config: defaultConfig(toolSchema) },
      { id: loopBackId, type: "loop_back", label: "回到循环", route: "循环流程", config: {} },
      { id: afterId, type: "tool", label: "循环后处理", route: "循环流程", config: defaultConfig(toolSchema) }
    );
    const startNode = state.nodes.find((n) => n.type === "start");
    const endNode = state.nodes.find((n) => n.type === "end");
    if (startNode && !state.edges.some((e) => e.from === startNode.id && e.to === loopId)) {
      state.edges.push({ from: startNode.id, to: loopId, condition: "", label: "进入循环", route: "循环流程" });
    }
    state.edges.push(
      { from: loopId, to: bodyId, condition: "body", label: "继续循环", route: "循环流程" },
      { from: bodyId, to: loopBackId, condition: "", label: "完成本轮", route: "循环流程" },
      { from: loopBackId, to: loopId, condition: "", label: "回到判断", route: "循环流程" },
      { from: loopId, to: afterId, condition: "exit", label: "退出循环", route: "循环流程" },
      { from: afterId, to: endNode.id, condition: "", label: "输出循环结果", route: "循环流程" }
    );
    const baseY = 120 + Math.floor(state.nodes.length / 4) * 190;
    state.positions.set(loopId, { x: 200, y: baseY });
    state.positions.set(bodyId, { x: 540, y: baseY });
    state.positions.set(loopBackId, { x: 880, y: baseY });
    state.positions.set(afterId, { x: 540, y: baseY + 190 });
    state.selectedNodeId = loopId;
    saveCanvasPositions();
    renderAll();
    zoomToFit();
    toast("已插入循环模板 — 可点「校验拓扑」检查");
  }

  function ensureStartEndNodes() {
    if (!state.nodes.some((n) => n.type === "start")) state.nodes.unshift({ id: uniqueNodeId("start"), type: "start", label: "开始入口", route: "", config: {} });
    if (!state.nodes.some((n) => n.type === "end")) state.nodes.push({ id: uniqueNodeId("end"), type: "end", label: "结束输出", route: "", config: {} });
  }

  function uniqueNodeId(base) {
    let index = 1, id = `${base}_${index}`;
    while (state.nodes.some((n) => n.id === id)) { index += 1; id = `${base}_${index}`; }
    return id;
  }

  function addNode(type) {
    recordWorkflowUndo("添加节点");
    const baseId = type.replace(/[^a-zA-Z0-9_]/g, "_") || "node";
    const id = uniqueNodeId(baseId);
    const node = { id, type, label: defaultNodeLabel(type), route: "", config: defaultConfig(schemaForType(type)) };
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
      const condition = state.connectSourceBranch || "";
      const duplicate = state.edges.some((e) =>
        e.from === state.connectSourceId && e.to === nodeId && (e.condition || "") === condition);
      if (!duplicate) {
        const sourceNode = state.nodes.find((node) => node.id === state.connectSourceId);
        recordWorkflowUndo("连接节点");
        state.edges.push({ from: state.connectSourceId, to: nodeId, condition, label: "", route: cleanText(sourceNode?.route) });
        toast("已连线");
      }
      state.connectSourceId = null;
      state.connectSourceBranch = "";
    }
    state.selectedNodeId = nodeId;
    renderNodes();
    renderEdges();
    renderInspector();
  }

  function deleteSelectedNode() {
    const node = findSelectedNode();
    if (!node || node.type === "start" || node.type === "end") return;
    recordWorkflowUndo("删除节点");
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
    recordWorkflowUndo("编辑技术 ID");
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
  // 自然语言生成工作流
  // ============================================================
  function openWorkflowGenerator() {
    workflowGeneratorMode = "design";
    els.definitionHistory?.classList.add("hidden");
    els.nodeInspector?.classList.add("hidden");
    els.workflowGenerator?.classList.remove("hidden");
    els.generatorPreview?.classList.add("hidden");
    resetPendingWorkflowSpec();
    restoreWorkflowGeneratorDesignControls();
    window.setTimeout(() => els.generatorPrompt?.focus(), 0);
  }

  function closeWorkflowGenerator() {
    els.workflowGenerator?.classList.add("hidden");
  }

  async function generateWorkflowFromPrompt() {
    const prompt = els.generatorPrompt?.value.trim() || "";
    if (!prompt) { toast("请先输入工作流描述", true); return; }
    const lockedSpec = pendingWorkflowSpec?.prompt === prompt ? pendingWorkflowSpec.response : null;
    if (els.generatorApply) {
      els.generatorApply.disabled = true;
      els.generatorApply.textContent = lockedSpec ? "生成中" : "分析中";
    }
    if (els.generatorPreview) {
      els.generatorPreview.classList.remove("hidden");
      renderGeneratorStreamingPreview(lockedSpec ? "正在按锁定规格生成工作流…" : "正在梳理需求边界与规格…", "");
    }
    try {
      if (!lockedSpec) {
        const specDraft = await draftWorkflowSpecification(prompt);
        if (specDraft.status === "NEEDS_CLARIFICATION") {
          pendingWorkflowSpec = null;
          renderGeneratorClarification(specDraft);
          toast("需要先确认几个边界，再生成工作流", true);
          return;
        }
        pendingWorkflowSpec = { prompt, response: specDraft };
        renderGeneratorLockedSpec(specDraft);
        if (els.generatorApply) els.generatorApply.textContent = "按规格生成";
        toast("规格已确认，再点「按规格生成」生成画布");
        return;
      }
      const response = await streamWorkflowGeneration(
        lockedGenerationPrompt(lockedSpec, prompt),
        canonicalWorkflowLockedSpec(lockedSpec?.spec ?? lockedSpec));
      const applied = handleWorkflowAiOutcome(response, applyGeneratedWorkflow,
        "已生成到画布，可继续编辑或保存");
      if (applied) pendingWorkflowSpec = null;
    } catch (error) {
      latestWorkflowRepairError = error.message || "自然语言生成失败";
      renderGeneratorStreamFailure(error.message);
      toast(error.message, true);
    } finally {
      if (els.generatorApply) {
        els.generatorApply.disabled = false;
        els.generatorApply.textContent = pendingWorkflowSpec ? "按规格生成" : "生成新画布";
      }
    }
  }

  async function editWorkflowFromPrompt() {
    const prompt = els.generatorPrompt?.value.trim() || "";
    if (!prompt) { toast("请先输入修改要求", true); return; }
    if (!state.nodes.length) { toast("当前画布为空，请先生成或添加节点", true); return; }
    const previousText = els.generatorEditCurrent?.textContent;
    if (els.generatorEditCurrent) {
      els.generatorEditCurrent.disabled = true;
      els.generatorEditCurrent.textContent = "修改中";
    }
    if (els.generatorApply) els.generatorApply.disabled = true;
    if (els.generatorPreview) {
      els.generatorPreview.classList.remove("hidden");
      renderGeneratorStreamingPreview("正在连接工作流编辑流…", "");
    }
    try {
      const response = await streamWorkflowEdit(prompt);
      handleWorkflowAiOutcome(response, applyEditedWorkflow,
        "已按自然语言修改当前画布，可撤回或保存");
    } catch (error) {
      latestWorkflowRepairError = error.message || "自然语言编辑失败";
      renderGeneratorStreamFailure(error.message);
      toast(error.message, true);
    } finally {
      if (els.generatorEditCurrent) {
        els.generatorEditCurrent.disabled = false;
        els.generatorEditCurrent.textContent = previousText || "修改当前画布";
      }
      if (els.generatorApply) els.generatorApply.disabled = false;
    }
  }

  async function streamWorkflowGeneration(prompt, lockedSpec) {
    return streamWorkflowAi(API.generateWorkflowStream, { prompt, lockedSpec });
  }

  async function draftWorkflowSpecification(prompt) {
    return requestJson(API.specDraft, { method: "POST", body: { prompt } });
  }

  function lockedGenerationPrompt(specDraft, fallbackPrompt) {
    const generated = cleanText(specDraft?.generationPrompt);
    if (generated) return generated;
    const specText = specDraft?.spec ? JSON.stringify(specDraft.spec, null, 2) : "";
    return `按以下已确认规格生成工作流：\n${specText || fallbackPrompt}`;
  }

  async function streamWorkflowEdit(prompt) {
    return streamWorkflowAi(API.editWorkflowStream, {
      prompt,
      name: els.definitionName?.value || "当前画布工作流",
      description: "来自当前画布",
      workflowDefinition: buildWorkflowDefinition(),
      lockedSpec: currentWorkflowLockedSpec
    });
  }

  function restoreWorkflowGeneratorDesignControls() {
    if (els.generatorPrompt) {
      els.generatorPrompt.placeholder = "输入你想创建或修改的工作流需求；点击 AI 修复时可补充你看到的问题。";
    }
    if (els.generatorEditCurrent) {
      els.generatorEditCurrent.disabled = false;
      els.generatorEditCurrent.textContent = "修改当前画布";
    }
    if (els.generatorApply) {
      els.generatorApply.disabled = false;
      els.generatorApply.textContent = pendingWorkflowSpec ? "按规格生成" : "生成新画布";
    }
  }

  function openWorkflowRepairPanel(errorHint = "") {
    if (!state.nodes.length) { toast("当前画布为空，请先生成或添加节点", true); return; }
    workflowGeneratorMode = "repair";
    els.definitionHistory?.classList.add("hidden");
    els.nodeInspector?.classList.add("hidden");
    els.workflowGenerator?.classList.remove("hidden");
    pendingWorkflowSpec = null;
    if (els.generatorPrompt) {
      els.generatorPrompt.value = "";
      els.generatorPrompt.placeholder = "描述你希望 AI 重点修复的问题，例如：条件没有走对、最终输出像 JSON、某个变量无效。";
    }
    if (els.generatorEditCurrent) {
      els.generatorEditCurrent.disabled = true;
      els.generatorEditCurrent.textContent = "等待确认";
    }
    if (els.generatorApply) {
      els.generatorApply.disabled = true;
      els.generatorApply.textContent = "修复准备中";
    }
    renderWorkflowRepairPreparation(errorHint);
    window.setTimeout(() => els.generatorPrompt?.focus(), 0);
  }

  function renderWorkflowRepairPreparation(errorHint = "") {
    if (!els.generatorPreview) return;
    const detectedError = cleanText(errorHint) || cleanText(latestWorkflowRepairError);
    els.generatorPreview.innerHTML = `
      <div class="generator-preview-title">AI 修复准备</div>
      <div class="generator-preview-meta">请先补充你看到的问题，Qwen 会同时读取当前画布、校验错误和这段说明。</div>
      ${detectedError ? `<div class="generator-preview-error">已捕获问题：${escapeHtml(detectedError)}</div>` : ""}
      <div class="generator-preview-hint">上方输入框可以留空；如果你已经知道哪里不对，写出来会明显提高修复命中率。</div>
      <button type="button" class="btn btn-primary generator-repair-action" data-confirm-ai-repair>
        开始 AI 修复
      </button>
    `;
    els.generatorPreview.querySelector("[data-confirm-ai-repair]")
      ?.addEventListener("click", () => void confirmWorkflowRepair(errorHint));
    els.generatorPreview.classList.remove("hidden");
  }

  async function confirmWorkflowRepair(errorHint = "") {
    const userRepairNote = cleanText(els.generatorPrompt?.value);
    await repairWorkflowWithAi(errorHint, userRepairNote);
  }

  async function repairWorkflowWithAi(errorHint = "", userRepairNote = "") {
    if (!state.nodes.length) { toast("当前画布为空，请先生成或添加节点", true); return; }
    const previousText = els.repairWorkflow?.textContent;
    if (els.repairWorkflow) {
      els.repairWorkflow.disabled = true;
      els.repairWorkflow.textContent = "AI 修复中";
    }
    if (els.generatorApply) els.generatorApply.disabled = true;
    els.workflowGenerator?.classList.remove("hidden");
    renderGeneratorStreamingPreview("正在收集当前画布错误并请求 Qwen 修复…", "");
    try {
      const request = await buildWorkflowRepairRequest(errorHint, userRepairNote);
      latestWorkflowRepairError = request.error;
      const response = await streamWorkflowRepair(request);
      handleWorkflowAiOutcome(response, applyEditedWorkflow,
        "AI 已修复当前画布，可继续检查、运行或保存");
    } catch (error) {
      latestWorkflowRepairError = error.message || "AI 修复失败";
      renderGeneratorStreamFailure(error.message);
      toast(error.message, true);
    } finally {
      if (els.repairWorkflow) {
        els.repairWorkflow.disabled = false;
        els.repairWorkflow.textContent = previousText || "AI 修复";
      }
      if (els.generatorApply) els.generatorApply.disabled = workflowGeneratorMode === "repair";
    }
  }

  async function buildWorkflowRepairRequest(errorHint = "", userRepairNote = "") {
    const workflowDefinition = buildWorkflowDefinition();
    let errorText = cleanText(errorHint) || latestWorkflowRepairError;
    const cleanRepairNote = cleanText(userRepairNote);
    try {
      const validation = await requestJson(API.validateWorkflow, {
        method: "POST",
        body: { workflowDefinition }
      });
      if (validation?.valid === false) {
        errorText = formatRepairValidationErrors(validation.errors) || errorText || "工作流校验未通过";
      }
    } catch (error) {
      errorText = error.message || errorText || "工作流校验失败";
    }
    if (!cleanText(errorText)) {
      const issues = workflowIssues().map((issue) => issue.message).filter(Boolean).join("；");
      errorText = issues || "用户点击 AI 修复，请检查当前画布是否存在变量、结构化输出、分支连线或最终回复问题。";
    }
    if (cleanRepairNote) {
      errorText = `${errorText}\n用户补充问题：${cleanRepairNote}`;
    }
    const prompt = `修复当前工作流「${normalizeWorkflowName(els.definitionName?.value)}」，保持现有业务目标。`
      + (cleanRepairNote ? `\n用户补充问题：${cleanRepairNote}` : "");
    return {
      prompt,
      error: errorText,
      name: normalizeWorkflowName(els.definitionName?.value),
      description: "来自当前画布",
      workflowDefinition,
      lockedSpec: currentWorkflowLockedSpec
    };
  }

  async function streamWorkflowRepair(request) {
    return streamWorkflowAi(API.repairWorkflowStream, request);
  }

  async function streamWorkflowAi(url, body) {
    const chunks = [];
    let status = "正在连接工作流 AI 流…";
    let finalResponse = null;
    const response = await fetch(url, {
      method: "POST",
      headers: authHeaders({ Accept: "text/event-stream", "Content-Type": "application/json" }),
      body: JSON.stringify(body)
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    await consumeSse(response, (eventName, data) => {
      if (eventName === "status" || eventName === "phase") {
        status = workflowPhaseMessage(data, status);
        renderGeneratorStreamingPreview(status, chunks.join(""));
      }
      if (eventName === "message" && data?.delta) {
        chunks.push(data.delta);
        renderGeneratorStreamingPreview(status, chunks.join(""));
      }
      if (eventName === "done") {
        finalResponse = data?.response || data;
      }
      if (eventName === "error") {
        const businessResponse = data?.response || data?.data || data;
        if (businessResponse?.status === "BLOCKED" || businessResponse?.status === "INFRA_ERROR") {
          finalResponse = businessResponse;
          return;
        }
        throw new Error(data?.error || data?.message || "流式生成失败");
      }
    });
    if (!finalResponse) throw new Error("AI 连接中断，已保留上方输出片段，请重试");
    return finalResponse;
  }

  function workflowPhaseMessage(data, fallback) {
    const phase = cleanText(data?.phase || data?.stage || data?.status).toUpperCase();
    const phaseLabels = {
      SPEC: "需求分析",
      SPECIFICATION: "需求分析",
      CONTEXT: "装载节点、工具与规则上下文",
      GENERATION: "生成候选工作流",
      MODEL_GENERATION: "生成候选工作流",
      STATIC: "静态治理检查",
      STATIC_GOVERNANCE: "静态治理检查",
      GOVERNANCE_EVALUATION: "治理检查与自动测试",
      EVALUATION: "自动测试",
      TEST: "自动测试",
      REPAIR: "自动修复",
      VERIFY: "修复后验证",
      COMPLETE: "治理检查完成"
    };
    const message = cleanText(data?.message);
    const phaseLabel = phaseLabels[phase];
    if (phaseLabel && message) return `${phaseLabel} · ${message}`;
    return message || phaseLabel || fallback || "正在处理工作流…";
  }

  function renderGeneratorStreamingPreview(status, rawText) {
    if (!els.generatorPreview) return;
    const visibleText = rawText || "等待模型输出…";
    els.generatorPreview.innerHTML = `
      <div class="generator-preview-title">智能体搭建助手正在处理工作流</div>
      <div class="generator-preview-meta">${escapeHtml(status)}</div>
      <pre class="generator-stream-output">${escapeHtml(visibleText)}</pre>
    `;
    els.generatorPreview.classList.remove("hidden");
    const streamOutput = els.generatorPreview.querySelector(".generator-stream-output");
    if (streamOutput) streamOutput.scrollTop = streamOutput.scrollHeight;
    els.generatorPreview.scrollTop = els.generatorPreview.scrollHeight;
  }

  function renderGeneratorStreamFailure(message) {
    if (!els.generatorPreview) return;
    const errorMessage = message || "生成失败，请重试";
    const existingOutput = els.generatorPreview.querySelector(".generator-stream-output");
    const notice = `
      <div class="generator-preview-error">
        ${escapeHtml(errorMessage)}。已保留上方生成片段，可直接重试。
      </div>
      <button type="button" class="btn btn-ghost generator-repair-action" data-ai-repair-workflow>
        AI 修复当前画布
      </button>
    `;
    if (existingOutput) {
      els.generatorPreview.querySelector(".generator-preview-error")?.remove();
      els.generatorPreview.querySelector("[data-ai-repair-workflow]")?.remove();
      els.generatorPreview.insertAdjacentHTML("beforeend", notice);
    } else {
      els.generatorPreview.innerHTML = `
        <div class="generator-preview-title">生成暂未完成</div>
        ${notice}
      `;
    }
    els.generatorPreview.querySelector("[data-ai-repair-workflow]")
      ?.addEventListener("click", () => openWorkflowRepairPanel(errorMessage));
    els.generatorPreview.classList.remove("hidden");
    els.generatorPreview.scrollTop = els.generatorPreview.scrollHeight;
  }

  function renderGeneratorClarification(specDraft) {
    if (!els.generatorPreview) return;
    const clarifications = clarificationItemsForDraft(specDraft);
    els.generatorPreview.innerHTML = `
      <div class="generator-preview-title">需要先确认几个边界</div>
      <div class="generator-preview-meta">${escapeHtml(specDraft?.summary || "我还不能安全生成工作流")}</div>
      <div class="generator-preview-hint">可点击下面选项，系统会把答案补充到上方描述里；也可以填写补充说明，然后再次点击生成新画布。</div>
      <div class="generator-clarification-list">
        ${clarifications.map(renderGeneratorClarificationCard).join("")}
      </div>
    `;
    els.generatorPreview.querySelectorAll("[data-clarification-option]").forEach((button) => {
      button.addEventListener("click", () => {
        const clarification = clarifications[Number(button.dataset.clarificationOption)];
        const option = clarification?.options?.[Number(button.dataset.optionIndex)];
        appendClarificationOptionToPrompt(clarification?.question, option);
        button.classList.add("selected");
      });
    });
    els.generatorPreview.querySelectorAll("[data-clarification-freeform]").forEach((button) => {
      button.addEventListener("click", () => {
        const index = Number(button.dataset.clarificationFreeform);
        const clarification = clarifications[index];
        const input = els.generatorPreview.querySelector(`[data-clarification-freeform-input="${index}"]`);
        const answer = cleanText(input?.value);
        if (!answer) { toast("请先填写补充说明", true); return; }
        appendClarificationOptionToPrompt(clarification?.question, answer);
      });
    });
    els.generatorPreview.classList.remove("hidden");
  }

  function clarificationItemsForDraft(specDraft) {
    const clarifications = Array.isArray(specDraft?.clarifications) ? specDraft.clarifications : [];
    const normalized = clarifications.map((item) => ({
      question: cleanText(item?.question),
      options: Array.isArray(item?.options) ? item.options.map(cleanText).filter(Boolean).slice(0, 3) : [],
      freeformPrompt: cleanText(item?.freeformPrompt) || "也可以填写你的特殊要求、部门边界或接入系统。"
    })).filter((item) => item.question);
    if (normalized.length) return normalized;
    const questions = Array.isArray(specDraft?.questions) ? specDraft.questions : [];
    return questions.map((question) => ({
      question: cleanText(question),
      options: [],
      freeformPrompt: "也可以填写你的特殊要求、部门边界或接入系统。"
    })).filter((item) => item.question);
  }

  function renderGeneratorClarificationCard(clarification, index) {
    const options = clarification.options || [];
    return `
      <div class="generator-clarification-card">
        <div class="generator-clarification-question">${index + 1}. ${escapeHtml(clarification.question)}</div>
        ${options.length ? `
          <div class="generator-option-list" aria-label="可点击下面选项">
            ${options.map((option, optionIndex) => `
              <button type="button" class="generator-option-button"
                      data-clarification-option="${index}" data-option-index="${optionIndex}">
                ${escapeHtml(option)}
              </button>
            `).join("")}
          </div>
        ` : "<div class=\"generator-preview-meta\">模型没有给出选项，可直接填写补充说明。</div>"}
        <label class="generator-freeform-field">
          <span>补充说明</span>
          <textarea class="generator-freeform-input" rows="2"
                    data-clarification-freeform-input="${index}"
                    placeholder="${escapeHtml(clarification.freeformPrompt)}"></textarea>
        </label>
        <button type="button" class="generator-freeform-button" data-clarification-freeform="${index}">
          补充到上方描述
        </button>
      </div>
    `;
  }

  function appendClarificationOptionToPrompt(question, answer) {
    if (!els.generatorPrompt) return;
    const cleanQuestion = cleanText(question);
    const cleanAnswer = cleanText(answer);
    if (!cleanQuestion || !cleanAnswer) return;
    const prefix = `- ${cleanQuestion}：`;
    const answerLine = `${prefix}${cleanAnswer}`;
    const lines = els.generatorPrompt.value.trimEnd().split(/\r?\n/);
    const existingIndex = lines.findIndex((line) => cleanText(line).startsWith(prefix));
    if (existingIndex >= 0) {
      lines[existingIndex] = answerLine;
    } else {
      if (!lines.some((line) => cleanText(line) === "补充说明：")) {
        if (lines.length && cleanText(lines[lines.length - 1])) lines.push("");
        lines.push("补充说明：");
      }
      lines.push(answerLine);
    }
    els.generatorPrompt.value = lines.join("\n").trimStart();
    els.generatorPrompt.dispatchEvent(new Event("input", { bubbles: true }));
    els.generatorPrompt.focus();
    toast("已补充到上方描述");
  }

  function renderGeneratorLockedSpec(specDraft) {
    if (!els.generatorPreview) return;
    const specRows = Object.entries(specDraft?.spec || {})
      .filter(([, value]) => value !== null && value !== undefined && String(formatSpecValue(value)).trim())
      .map(([key, value]) => `
        <div class="generator-spec-row">
          <span>${escapeHtml(specKeyLabel(key))}</span>
          <strong>${escapeHtml(formatSpecValue(value))}</strong>
        </div>
      `).join("");
    els.generatorPreview.innerHTML = `
      <div class="generator-preview-title">规格确认</div>
      <div class="generator-preview-meta">${escapeHtml(specDraft?.summary || "已形成可生成的工作流规格")}</div>
      <div class="generator-spec-card">${specRows || "<div class=\"generator-preview-meta\">规格已锁定。</div>"}</div>
      <div class="generator-preview-test">下一步：按规格生成。生成后系统仍会自动校验并试运行。</div>
    `;
    els.generatorPreview.classList.remove("hidden");
  }

  function formatSpecValue(value) {
    if (Array.isArray(value)) {
      return value.map(formatSpecValue).filter(Boolean).join("；");
    }
    if (value && typeof value === "object") {
      return Object.entries(value)
        .map(([key, child]) => `${specKeyLabel(key)}：${formatSpecValue(child)}`)
        .filter((entry) => entry.trim())
        .join("；");
    }
    return value == null ? "" : String(value);
  }

  function specKeyLabel(key) {
    const labels = {
      domain: "业务领域",
      goal: "目标",
      inputs: "输入",
      knownInputs: "已知输入",
      requiredCapabilities: "必需能力",
      outputAudience: "输出对象",
      classificationRules: "分类规则",
      routingRules: "路由规则",
      actions: "业务动作",
      integrations: "外部系统",
      failurePolicy: "失败策略",
      outputContract: "输出约定",
      testCases: "测试样例",
      nonGoals: "不做范围",
      missingDecisions: "待确认"
    };
    return labels[key] || key;
  }

  function resetPendingWorkflowSpec() {
    pendingWorkflowSpec = null;
    if (workflowGeneratorMode === "repair") return;
    if (els.generatorApply && !els.generatorApply.disabled) {
      els.generatorApply.textContent = "生成新画布";
    }
  }

  function isWorkflowResponseReady(response) {
    return response?.status === "READY";
  }

  function hasWorkflowCandidate(response) {
    const definition = response?.workflowDefinition;
    return Array.isArray(definition?.nodes)
      && definition.nodes.length > 0
      && Array.isArray(definition?.edges);
  }

  function handleWorkflowAiOutcome(response, applyWorkflow, successMessage) {
    if (!isWorkflowResponseReady(response)) {
      const status = cleanText(response?.status).toUpperCase() || "BLOCKED";
      const candidateAvailable = hasWorkflowCandidate(response);
      if (candidateAvailable) applyWorkflow(response);
      const message = candidateAvailable
        ? status === "INFRA_ERROR"
          ? `${governanceInfrastructureMessage(response)} 已生成候选蓝图到画布，可继续编辑和保存。`
          : "自动修复未完全通过，已生成候选蓝图到画布，可继续编辑和保存。"
        : status === "INFRA_ERROR"
          ? governanceInfrastructureMessage(response)
          : "治理检查未通过，未产生可用候选蓝图；需求描述仍在输入框中。";
      latestWorkflowRepairError = message;
      renderGeneratorGovernanceReport(response);
      toast(message, true);
      return candidateAvailable;
    }
    applyWorkflow(response);
    renderGeneratorPreview(response);
    toast(successMessage);
    return true;
  }

  function applyGeneratedWorkflow(response) {
    const definition = response?.workflowDefinition;
    if (!definition?.nodes || !definition?.edges) throw new Error("生成结果缺少工作流定义");
    const needsRepair = !isWorkflowResponseReady(response);
    state.definitionId = null;
    state.definitionVersion = null;
    state.definitionStatus = null;
    state.lastRunId = null;
    state.connectSourceId = null;
    state.connectSourceBranch = "";
    state.selectedNodeId = null;
    restoreWorkflowLockedSpec(response);
    if (els.definitionSelect) els.definitionSelect.value = "";
    if (els.definitionName) els.definitionName.value = response.name || "自然语言生成工作流";
    hydrateWorkflow(definition, { loadSavedPositions: false });
    setWorkflowRunVariables(response.variables, { preserveValues: false });
    if (els.runOutput) els.runOutput.textContent = "{}";
    if (els.runResult) {
      els.runResult.className = "result-card empty-result";
      els.runResult.textContent = needsRepair
        ? "候选蓝图已保留；请先编辑或 AI 修复，通过校验后再运行。"
        : "生成后可直接运行，结果和每一步轨迹会显示在这里。";
    }
    if (els.traceSteps) els.traceSteps.innerHTML = "";
    els.definitionHistory?.classList.add("hidden");
    setWorkflowStatus(needsRepair ? "待修复草稿" : "生成草稿");
    bindAssistantWorkflowFromDefinition({ name: response.name || "自然语言生成工作流", status: "DRAFT" });
    renderDefinitionHistory([], []);
    renderAll();
    zoomToFit();
    saveCanvasPositions();
    return true;
  }

  function applyEditedWorkflow(response) {
    const definition = response?.workflowDefinition;
    if (!definition?.nodes || !definition?.edges) throw new Error("编辑结果缺少工作流定义");
    const needsRepair = !isWorkflowResponseReady(response);
    const previousPositions = new Map(state.positions);
    recordWorkflowUndo("自然语言编辑工作流");
    state.lastRunId = null;
    state.connectSourceId = null;
    state.connectSourceBranch = "";
    state.selectedNodeId = null;
    restoreWorkflowLockedSpec(response);
    if (els.definitionName) els.definitionName.value = response.name || els.definitionName.value || "当前画布工作流";
    hydrateWorkflow(definition, { loadSavedPositions: false, clearUndo: false });
    state.nodes.forEach((node) => {
      const existingPosition = previousPositions.get(node.id);
      if (existingPosition) state.positions.set(node.id, existingPosition);
    });
    setWorkflowRunVariables(response.variables, { preserveValues: true });
    if (els.runOutput) els.runOutput.textContent = "{}";
    if (els.runResult) {
      els.runResult.className = "result-card empty-result";
      els.runResult.textContent = needsRepair
        ? "修改候选蓝图已保留；请继续编辑或 AI 修复，通过校验后再运行。"
        : "自然语言编辑后可直接运行，结果和每一步轨迹会显示在这里。";
    }
    if (els.traceSteps) els.traceSteps.innerHTML = "";
    els.definitionHistory?.classList.add("hidden");
    setWorkflowStatus(needsRepair ? "待修复草稿" : "已修改");
    renderAll();
    zoomToFit();
    saveCanvasPositions();
    return true;
  }

  function renderGeneratorPreview(response) {
    if (!els.generatorPreview) return;
    const nodes = response?.workflowDefinition?.nodes || [];
    const edges = response?.workflowDefinition?.edges || [];
    const notes = Array.isArray(response?.notes) ? response.notes : [];
    els.generatorPreview.innerHTML = `
      <div class="generator-preview-title">${escapeHtml(response?.name || "已生成工作流")}</div>
      <div class="generator-preview-meta">${nodes.length} 个节点 · ${edges.length} 条连线</div>
      ${governanceReportMarkup(response)}
      ${notes.length ? `<ul>${notes.map((note) => `<li>${escapeHtml(note)}</li>`).join("")}</ul>` : ""}
    `;
    els.generatorPreview.classList.remove("hidden");
  }

  function renderGeneratorGovernanceReport(response, options = {}) {
    if (!els.generatorPreview) return;
    const title = options.title || response?.name || "工作流治理结果";
    els.generatorPreview.innerHTML = `
      <div class="generator-preview-title">${escapeHtml(title)}</div>
      ${governanceReportMarkup(response)}
    `;
    els.generatorPreview.classList.remove("hidden");
    els.generatorPreview.scrollTop = 0;
  }

  function governanceSummary(response) {
    const rawStatus = cleanText(response?.status).toUpperCase();
    const status = ["READY", "BLOCKED", "INFRA_ERROR"].includes(rawStatus) ? rawStatus : "BLOCKED";
    const testResults = Array.isArray(response?.testResults) ? response.testResults : [];
    const legacyTestPassed = testResults.length === 0 && status === "READY" && Boolean(response?.testResult);
    const total = testResults.length || (legacyTestPassed ? 1 : 0);
    const passed = testResults.filter((result) => result?.status === "PASSED").length
      + (legacyTestPassed ? 1 : 0);
    const findings = governanceFindings(response);
    return {
      status,
      passed,
      total,
      blockers: findings.filter((finding) => finding?.severity === "BLOCK"),
      warnings: findings.filter((finding) => finding?.severity === "WARN"),
      packVersions: governancePackVersions(response),
      repairAttempts: Math.max(0, Number(response?.repairAttempts || 0)),
      testResults
    };
  }

  function governanceFindings(response) {
    const report = response?.governanceReport || {};
    if (Array.isArray(report.findings)) return report.findings;
    const blockers = Array.isArray(report.blockers) ? report.blockers : [];
    const warnings = Array.isArray(report.warnings) ? report.warnings : [];
    return [
      ...blockers.map((finding) => ({ ...finding, severity: finding?.severity || "BLOCK" })),
      ...warnings.map((finding) => ({ ...finding, severity: finding?.severity || "WARN" }))
    ];
  }

  function governancePackVersions(response) {
    const report = response?.governanceReport || {};
    const value = response?.activeRulePacks || response?.rulePacks
      || response?.rulePackVersions || response?.packVersions || response?.activePacks
      || report.activeRulePacks || report.rulePacks
      || report.rulePackVersions || report.packVersions || report.activePacks || [];
    const entries = Array.isArray(value)
      ? value
      : typeof value === "string"
        ? [value]
        : value && typeof value === "object"
          ? Object.entries(value).map(([id, version]) => ({ id, version }))
          : [];
    return [...new Set(entries.map((entry) => {
      if (typeof entry === "string") return cleanText(entry);
      const id = cleanText(entry?.id || entry?.packId || entry?.name);
      const version = cleanText(entry?.version);
      return id && version ? `${id}@${version}` : id || version;
    }).filter(Boolean))];
  }

  function governanceReportMarkup(response) {
    const summary = governanceSummary(response);
    const tavilyConfigurationRequired = governanceNeedsTavilyConfiguration(summary);
    const statusMessage = summary.status === "READY"
      ? "治理检查通过，候选工作流已完成自动测试。"
      : summary.status === "INFRA_ERROR"
        ? governanceInfrastructureMessage(response, summary)
        : "发现需要修复的设计问题，候选蓝图仍可编辑和保存。";
    const findingRows = [...summary.blockers, ...summary.warnings].slice(0, 4).map((finding) => `
      <li>
        <strong>${finding?.severity === "BLOCK" ? "阻断" : "警告"}</strong>
        ${escapeHtml(finding?.message || finding?.ruleId || "未提供说明")}
      </li>
    `).join("");
    const packVersions = summary.packVersions.length ? summary.packVersions.join("、") : "未提供";
    return `
      <section class="governance-report" data-governance-status="${escapeHtml(summary.status)}">
        <div class="governance-report-head">
          <span class="governance-status governance-status-${summary.status.toLowerCase()}">${escapeHtml(summary.status)}</span>
          <span>${escapeHtml(statusMessage)}</span>
        </div>
        ${tavilyConfigurationRequired
          ? '<button class="btn btn-ghost governance-tavily-action" type="button" data-open-tavily-settings>去配置 Tavily</button>'
          : ""}
        <div class="governance-summary-grid">
          <span><small>自动测试</small><strong>${summary.passed}/${summary.total}</strong></span>
          <span><small>阻断</small><strong>${summary.blockers.length}</strong></span>
          <span><small>警告</small><strong>${summary.warnings.length}</strong></span>
          <span><small>自动修复</small><strong>${summary.repairAttempts} 次</strong></span>
        </div>
        <div class="governance-pack-versions"><strong>规则包版本</strong>${escapeHtml(packVersions)}</div>
        ${findingRows ? `<ul class="governance-findings">${findingRows}</ul>` : ""}
        ${governanceEvidenceMarkup(response, summary)}
      </section>
    `;
  }

  function governanceEvidenceMarkup(response, summary) {
    const findings = [...summary.blockers, ...summary.warnings];
    const notes = summary.status === "INFRA_ERROR" ? governanceResponseNotes(response) : [];
    if (!summary.testResults.length && !findings.length && !notes.length) return "";
    const findingEvidence = findings.map((finding) => `
      <div class="governance-evidence-item">
        <strong>${escapeHtml(finding?.ruleId || "治理规则")}</strong>
        <span>${escapeHtml(finding?.repairHint || finding?.message || "未提供修复建议")}</span>
        ${finding?.evidence && Object.keys(finding.evidence).length
          ? `<pre>${escapeHtml(formatGovernanceValue(finding.evidence))}</pre>` : ""}
      </div>
    `).join("");
    const noteEvidence = notes.length ? `
      <div class="governance-evidence-item">
        <strong>基础设施错误详情</strong>
        ${notes.map((note) => `<span>${escapeHtml(note)}</span>`).join("")}
      </div>
    ` : "";
    const testEvidence = summary.testResults.map(governanceTestResultMarkup).join("");
    return `
      <details class="governance-test-details">
        <summary>展开测试证据与运行详情</summary>
        <div class="governance-evidence-list">
          ${noteEvidence}
          ${findingEvidence}
          ${testEvidence}
        </div>
      </details>
    `;
  }

  function governanceTestResultMarkup(result, index) {
    const runIds = Array.isArray(result?.attemptRunIds) ? result.attemptRunIds : [];
    const path = Array.isArray(result?.executedPath) ? result.executedPath : [];
    const assertions = Array.isArray(result?.assertions) ? result.assertions : [];
    const assertionEvidence = assertions.length ? assertions.map((assertion) => `
      <div class="governance-assertion">
        <strong>${escapeHtml(assertion?.assertionId || assertion?.type || "断言")}</strong>
        <span>${escapeHtml(assertion?.status || "UNKNOWN")}</span>
        <small>期望：${escapeHtml(formatGovernanceValue(assertion?.expected))}</small>
        <small>实际：${escapeHtml(formatGovernanceValue(assertion?.actual))}</small>
      </div>
    `).join("") : "<span>未返回断言证据</span>";
    return `
      <article class="governance-evidence-item">
        <div class="governance-evidence-title">
          <strong>${escapeHtml(result?.caseId || `测试 ${index + 1}`)}</strong>
          <span>${escapeHtml(result?.status || "UNKNOWN")}</span>
        </div>
        <dl class="governance-evidence-fields">
          <dt>错误来源</dt><dd>${escapeHtml(result?.errorOrigin || "未返回")}</dd>
          <dt>错误代码</dt><dd>${escapeHtml(result?.errorCode || "未返回")}</dd>
          <dt>测试输入</dt><dd><pre>${escapeHtml(formatGovernanceValue(result?.input))}</pre></dd>
          <dt>证据</dt><dd>${assertionEvidence}</dd>
          <dt>运行路径</dt><dd>${escapeHtml(path.length ? path.join(" → ") : "未返回")}</dd>
          <dt>输出</dt><dd><pre>${escapeHtml(formatGovernanceValue(result?.output ?? result?.outputSummary))}</pre></dd>
          <dt>runId</dt><dd>${escapeHtml(runIds.length ? runIds.join("、") : "未返回")}</dd>
        </dl>
      </article>
    `;
  }

  function governanceInfrastructureMessage(response, summary = governanceSummary(response)) {
    if (governanceNeedsTavilyConfiguration(summary)) {
      return "Tavily 尚未配置，请先在设置页配置 Tavily API Key。";
    }
    const notes = governanceResponseNotes(response);
    if (notes.some((note) => note.toLowerCase().includes("streaming call failed"))) {
      return "模型流式生成失败，尚未产生候选工作流；需求描述仍在输入框中。";
    }
    return "自动测试环境暂时不可用，请稍后重试。";
  }

  function governanceNeedsTavilyConfiguration(summary) {
    return summary.testResults.some((result) =>
      cleanText(result?.errorCode).toUpperCase() === "TAVILY_NOT_CONFIGURED");
  }

  function openTavilySettings() {
    document.querySelector('[data-view="settings"]')?.click();
    requestAnimationFrame(() => {
      const input = document.getElementById("settings-tavily-key");
      input?.closest(".card")?.scrollIntoView({ behavior: "smooth", block: "center" });
      input?.focus({ preventScroll: true });
    });
  }

  function governanceResponseNotes(response) {
    return (Array.isArray(response?.notes) ? response.notes : [])
      .map((note) => cleanText(note))
      .filter(Boolean);
  }

  function formatGovernanceValue(value) {
    if (value === null || value === undefined || value === "") return "未返回";
    if (typeof value === "string") return value;
    try { return JSON.stringify(value, null, 2); }
    catch (error) { return String(value); }
  }

  // ============================================================
  // 校验 / 预览 / 保存 / 发布 / 运行
  // ============================================================
  async function validateWorkflow() {
    await runCommand({
      request: () => requestJson(API.validateWorkflow, { method: "POST", body: { workflowDefinition: buildWorkflowDefinition() } }),
      onSuccess: async (response) => {
        setWorkflowStatus(response.valid ? "Valid" : "Invalid");
        latestWorkflowRepairError = response.valid ? "" : (formatRepairValidationErrors(response.errors) || "工作流校验未通过");
        toast(response.valid ? "工作流校验通过" : "工作流校验未通过", !response.valid);
      },
      onError: (error) => {
        latestWorkflowRepairError = error.message || "工作流校验失败";
        setWorkflowStatus("Invalid");
        toast(error.message, true);
      }
    });
  }

  function formatRepairValidationErrors(errors) {
    if (!Array.isArray(errors) || errors.length === 0) return "";
    return errors
      .map((error) => cleanText(error?.message || error?.code || error))
      .filter(Boolean)
      .join("；");
  }

  async function saveDefinition() {
    const definitionName = normalizeWorkflowName(els.definitionName?.value);
    syncWorkflowNameInputs(definitionName);
    const body = {
      name: definitionName,
      description: "来自智能体工作台",
      workflowDefinition: buildWorkflowDefinition(),
      variables: effectiveWorkflowVariables(),
      lockedSpec: currentWorkflowLockedSpec
    };
    const url = state.definitionId ? `${API.definitions}/${encodeURIComponent(state.definitionId)}` : API.definitions;
    const method = state.definitionId ? "PUT" : "POST";
    await runCommand({
      request: () => requestJson(url, { method, body }),
      onSuccess: async (response) => {
        state.definitionId = response.definitionId;
        state.definitionVersion = response.version;
        state.definitionStatus = response.status;
        setWorkflowRunVariables(response.variables, { preserveValues: true });
        restoreWorkflowLockedSpec(response);
        bindAssistantWorkflowFromDefinition(response);
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
    if (state.definitionStatus === "PUBLISHED") {
      const version = state.definitionVersion ? `（v${state.definitionVersion}）` : "";
      toast(`当前版本已发布；画布有改动时请先保存，再发布新版本${version}`);
      return;
    }
    if (publishDefinitionInFlight) {
      toast("正在执行发布前检查，请等待当前请求完成");
      return;
    }

    publishDefinitionInFlight = true;
    if (els.publishDefinition) {
      els.publishDefinition.disabled = true;
      els.publishDefinition.setAttribute("aria-busy", "true");
      els.publishDefinition.textContent = "发布检查中…";
    }
    setWorkflowStatus("发布检查中…");
    toast("正在执行发布前治理检查和自动测试…");
    try {
      await runCommand({
        request: () => requestJson(API.publishDefinition(state.definitionId), { method: "POST" }),
        onSuccess: async (response) => {
          state.definitionVersion = response.version;
          state.definitionStatus = response.status;
          setWorkflowRunVariables(response.variables, { preserveValues: true });
          bindAssistantWorkflowFromDefinition(response);
          setWorkflowStatus(`${response.status || "PUBLISHED"} v${response.version}`);
          await loadDefinitions();
          await loadDefinitionHistory();
          toast("工作流已发布");
        },
        onError: (error) => {
          setWorkflowStatus(`${state.definitionStatus || "DRAFT"}${state.definitionVersion ? ` v${state.definitionVersion}` : ""}`);
          if (!renderPublishGovernanceFailure(error)) toast(error.message, true);
        }
      });
    } finally {
      publishDefinitionInFlight = false;
      if (els.publishDefinition) {
        els.publishDefinition.disabled = false;
        els.publishDefinition.removeAttribute("aria-busy");
        els.publishDefinition.textContent = "发布当前版本";
      }
    }
  }

  function renderPublishGovernanceFailure(error) {
    const code = cleanText(error?.code).toUpperCase();
    const blocked = code === "WORKFLOW_GOVERNANCE_BLOCKED";
    const infra = code === "WORKFLOW_GOVERNANCE_INFRA_ERROR"
      || (code.includes("WORKFLOW_GOVERNANCE") && code.includes("INFRA"));
    if (!blocked && !infra) return false;
    const outcome = error?.data && typeof error.data === "object" ? error.data : null;
    const message = infra
      ? "发布前自动测试环境暂时不可用，当前画布未受影响"
      : "发布前治理检查未通过，当前画布未受影响";
    latestWorkflowRepairError = cleanText(error?.message) || message;
    els.definitionHistory?.classList.add("hidden");
    els.nodeInspector?.classList.add("hidden");
    els.workflowGenerator?.classList.remove("hidden");
    if (outcome?.status) {
      renderGeneratorGovernanceReport(outcome, { title: "发布前治理检查未通过" });
    } else if (els.generatorPreview) {
      els.generatorPreview.innerHTML = `
        <div class="generator-preview-title">发布前治理检查未通过</div>
        <div class="generator-preview-meta">报告不可用：服务端没有返回本次发布检查的证据快照。</div>
      `;
      els.generatorPreview.classList.remove("hidden");
    }
    toast(message, true);
    return true;
  }
