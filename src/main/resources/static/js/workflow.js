"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};

  const AUTO_STRUCTURED_HIDDEN_FIELDS = new Set(["outputMode", "outputSchema", "writeState"]);
  const CUSTOMER_SERVICE_INTENT_CONTRACT = "customer_service_intent";
  const CUSTOMER_SERVICE_INTENTS = [
    "order_policy", "order_query", "need_order_id", "product_consult",
    "complaint", "human_transfer", "bug_feedback", "sales_lead", "chitchat"
  ];
  const CUSTOMER_SERVICE_SCHEMA_FIELDS = ["intent", "hasOrderId", "needsOrderId", "orderIds", "confidence"];

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
      els.definitionName.value = definition.name;
      hydrateWorkflow(definition.workflowDefinition);
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
    if (els.workflowInput) els.workflowInput.value = DEFAULT_RUN_INPUT;
    if (els.runOutput) els.runOutput.textContent = "{}";
    if (els.runResult) { els.runResult.className = "result-card empty-result"; els.runResult.textContent = "运行后在这里查看友好结果与每一步轨迹。"; }
    if (els.traceSteps) els.traceSteps.innerHTML = "";
    closeInspector();
    els.definitionHistory?.classList.add("hidden");
    setWorkflowStatus("Draft");
    renderDefinitionHistory([], []);
  }

  function hydrateWorkflow(definition, options = {}) {
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

    if (node.type === "condition") {
      renderConditionNodeConfig(node);
    } else {
      (schema.configFields || []).forEach((field) => {
        if (autoStructured && AUTO_STRUCTURED_HIDDEN_FIELDS.has(field.name)) return;
        const shell = fieldShell(configFieldLabel(field.name));
        let control;
        if (node.type === "subgraph" && field.name === "definitionId") {
          control = subgraphDefinitionControl(node.config.definitionId || "");
          control.addEventListener("change", () => {
            node.config.definitionId = control.value; node.config.version = null;
            renderInspector(); renderRouteMap(); renderNodes(); renderEdges();
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
          control.addEventListener("change", () => { node.config.version = control.value ? Number.parseInt(control.value, 10) : null; renderRouteMap(); renderNodes(); renderEdges(); });
        } else {
          control = controlForField(field, node.config[field.name]);
          control.addEventListener("change", () => { node.config[field.name] = parseControlValue(control.value, field.type); renderRouteMap(); renderNodes(); renderEdges(); });
        }
        shell.appendChild(control);
        els.inspectorForm.appendChild(shell);
      });
    }

    els.inspectorForm.appendChild(renderAdvancedNodeSettings(node));
    if (conditionNode) {
      els.inspectorForm.appendChild(renderConditionBranchEditor(node));
    } else {
      renderEdgeEditor(node);
    }
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
    const text = [node.id, node.label, node.route, config.prompt]
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
    els.inspectorForm.appendChild(conditionRuleControl(node.config, updatePreview, refreshPanel));
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
      target.addEventListener("change", () => { edge.to = target.value; refreshAfterEdgeChange(); });
      const remove = document.createElement("button");
      remove.type = "button";
      remove.className = "icon-btn-sm next-step-remove";
      remove.title = "删除这条连线";
      remove.textContent = "×";
      remove.addEventListener("click", () => {
        const index = state.edges.indexOf(edge);
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
      from.addEventListener("change", () => { edge.from = from.value; refreshAfterEdgeChange(); });
      to.addEventListener("change", () => { edge.to = to.value; refreshAfterEdgeChange(); });
      condition.addEventListener("change", () => { edge.condition = condition.value.trim(); renderRouteMap(); renderNodes(); renderEdges(); });
      labelInput.addEventListener("change", () => { edge.label = cleanText(labelInput.value); renderRouteMap(); renderNodes(); renderEdges(); renderInspector(); });
      routeInput.addEventListener("change", () => { edge.route = cleanText(routeInput.value); renderRouteMap(); renderNodes(); renderEdges(); renderInspector(); });
      remove.addEventListener("click", () => { state.edges.splice(index, 1); refreshAfterEdgeChange(); });
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
  // 自然语言生成工作流
  // ============================================================
  function openWorkflowGenerator() {
    els.definitionHistory?.classList.add("hidden");
    els.nodeInspector?.classList.add("hidden");
    els.workflowGenerator?.classList.remove("hidden");
    els.generatorPreview?.classList.add("hidden");
    if (els.generatorPrompt && !els.generatorPrompt.value.trim()) {
      els.generatorPrompt.value = "先检索知识库文档，再让大模型结合上下文回答用户问题";
    }
    window.setTimeout(() => els.generatorPrompt?.focus(), 0);
  }

  function closeWorkflowGenerator() {
    els.workflowGenerator?.classList.add("hidden");
  }

  async function generateWorkflowFromPrompt() {
    const prompt = els.generatorPrompt?.value.trim() || "";
    if (!prompt) { toast("请先输入工作流描述", true); return; }
    const previousText = els.generatorApply?.textContent;
    if (els.generatorApply) {
      els.generatorApply.disabled = true;
      els.generatorApply.textContent = "生成中";
    }
    if (els.generatorPreview) {
      els.generatorPreview.classList.remove("hidden");
      renderGeneratorStreamingPreview("正在连接工作流生成流…", "");
    }
    try {
      const response = await streamWorkflowGeneration(prompt);
      applyGeneratedWorkflow(response);
      renderGeneratorPreview(response);
      toast("已生成到画布，可继续编辑或保存");
    } catch (error) {
      if (els.generatorPreview) els.generatorPreview.textContent = error.message;
      toast(error.message, true);
    } finally {
      if (els.generatorApply) {
        els.generatorApply.disabled = false;
        els.generatorApply.textContent = previousText || "生成到画布";
      }
    }
  }

  async function streamWorkflowGeneration(prompt) {
    const chunks = [];
    let status = "正在连接工作流生成流…";
    let finalResponse = null;
    const response = await fetch(API.generateWorkflowStream, {
      method: "POST",
      headers: authHeaders({ Accept: "text/event-stream", "Content-Type": "application/json" }),
      body: JSON.stringify({ prompt })
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    await consumeSse(response, (eventName, data) => {
      if (eventName === "status") {
        status = data?.message || status;
        renderGeneratorStreamingPreview(status, chunks.join(""));
      }
      if (eventName === "message" && data?.delta) {
        chunks.push(data.delta);
        renderGeneratorStreamingPreview(status, chunks.join(""));
      }
      if (eventName === "done") {
        finalResponse = data?.response;
      }
      if (eventName === "error") {
        throw new Error(data?.error || data?.message || "流式生成失败");
      }
    });
    if (!finalResponse) throw new Error("流式生成未返回工作流结果");
    return finalResponse;
  }

  function renderGeneratorStreamingPreview(status, rawText) {
    if (!els.generatorPreview) return;
    const visibleText = rawText || "等待模型输出…";
    els.generatorPreview.innerHTML = `
      <div class="generator-preview-title">AI 正在编排工作流</div>
      <div class="generator-preview-meta">${escapeHtml(status)}</div>
      <pre class="generator-stream-output">${escapeHtml(visibleText)}</pre>
    `;
    els.generatorPreview.classList.remove("hidden");
    const streamOutput = els.generatorPreview.querySelector(".generator-stream-output");
    if (streamOutput) streamOutput.scrollTop = streamOutput.scrollHeight;
    els.generatorPreview.scrollTop = els.generatorPreview.scrollHeight;
  }

  function applyGeneratedWorkflow(response) {
    const definition = response?.workflowDefinition;
    if (!definition?.nodes || !definition?.edges) throw new Error("生成结果缺少工作流定义");
    state.definitionId = null;
    state.definitionVersion = null;
    state.definitionStatus = null;
    state.lastRunId = null;
    state.connectSourceId = null;
    state.connectSourceBranch = "";
    state.selectedNodeId = null;
    if (els.definitionSelect) els.definitionSelect.value = "";
    if (els.definitionName) els.definitionName.value = response.name || "自然语言生成工作流";
    hydrateWorkflow(definition, { loadSavedPositions: false });
    if (els.workflowInput) els.workflowInput.value = DEFAULT_RUN_INPUT;
    if (els.runOutput) els.runOutput.textContent = "{}";
    if (els.runResult) {
      els.runResult.className = "result-card empty-result";
      els.runResult.textContent = "生成后可直接运行，结果和每一步轨迹会显示在这里。";
    }
    if (els.traceSteps) els.traceSteps.innerHTML = "";
    if (state.inputMode === "form") renderRunInputForm();
    els.definitionHistory?.classList.add("hidden");
    setWorkflowStatus("生成草稿");
    bindAssistantWorkflowFromDefinition({ name: response.name || "自然语言生成工作流", status: "DRAFT" });
    renderDefinitionHistory([], []);
    renderAll();
    zoomToFit();
    saveCanvasPositions();
  }

  function renderGeneratorPreview(response) {
    if (!els.generatorPreview) return;
    const nodes = response?.workflowDefinition?.nodes || [];
    const edges = response?.workflowDefinition?.edges || [];
    const notes = Array.isArray(response?.notes) ? response.notes : [];
    els.generatorPreview.innerHTML = `
      <div class="generator-preview-title">${escapeHtml(response?.name || "已生成工作流")}</div>
      <div class="generator-preview-meta">${nodes.length} 个节点 · ${edges.length} 条连线</div>
      ${notes.length ? `<ul>${notes.map((note) => `<li>${escapeHtml(note)}</li>`).join("")}</ul>` : ""}
    `;
    els.generatorPreview.classList.remove("hidden");
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
    await runCommand({
      request: () => requestJson(API.publishDefinition(state.definitionId), { method: "POST" }),
      onSuccess: async (response) => {
        state.definitionVersion = response.version;
        state.definitionStatus = response.status;
        bindAssistantWorkflowFromDefinition(response);
        setWorkflowStatus(`${response.status || "PUBLISHED"} v${response.version}`);
        await loadDefinitions();
        await loadDefinitionHistory();
        toast("工作流已发布");
      },
      onError: (error) => toast(error.message, true)
    });
  }
