"use strict";

window.AgentWorkbench = window.AgentWorkbench || {};


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
        setRunStatus(`回放中 · ${response.runId ? response.runId.slice(0, 8) : ""}`);
        await animateRunOnCanvas(response.runId);
        setRunStatus(`完成 · ${response.runId ? response.runId.slice(0, 8) : ""}`);
        await loadDefinitionHistory();
        toast("工作流运行完成，已刷新运行后节点状态回放");
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
  // trace-driven highlighting / 事件回放式高亮: replay run-events after the synchronous run
  // completes, then settle the authoritative statuses + steps panel via refreshRunTrace. Falls
  // back to a plain refresh if the events stream is unavailable.
  async function animateRunOnCanvas(runId) {
    if (!runId) return;
    clearCanvasNodeStatuses();
    const events = [];
    try {
      const response = await fetch(API.workflowRunEvents(runId),
        { headers: authHeaders({ Accept: "text/event-stream" }) });
      if (response.ok && response.body) {
        await consumeSse(response, (event, data) => events.push({ event, data }));
      }
    } catch (error) { /* fall back to the plain trace refresh below */ }
    for (const { event, data } of events) {
      applyRunEventToCanvas(event, data);
      if (event === "node_started") await sleep(180);
    }
    await refreshRunTrace(runId);
  }

  function clearCanvasNodeStatuses() {
    document.querySelectorAll(".canvas-node").forEach((element) =>
      element.classList.remove("status-success", "status-failed", "status-running"));
  }

  function applyRunEventToCanvas(event, data) {
    if (event === "run_done") return;
    const nodeName = data && data.nodeName ? String(data.nodeName) : "";
    const nodeId = nodeName.startsWith("workflow_node_") ? nodeName.slice("workflow_node_".length) : nodeName;
    const element = els.nodeLayer?.querySelector(`[data-node-id="${cssEscape(nodeId)}"]`);
    if (!element) return;
    if (event === "node_started") {
      element.classList.add("status-running");
    } else if (event === "node_succeeded") {
      element.classList.remove("status-running");
      element.classList.add("status-success");
    } else if (event === "node_failed") {
      element.classList.remove("status-running");
      element.classList.add("status-failed");
    }
  }

  function sleep(ms) {
    return new Promise((resolve) => setTimeout(resolve, ms));
  }

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
      meta.className = "history-row-main";
      meta.innerHTML = `<div class="history-row-title"><strong>v${revision.version}</strong><span>${escapeHtml(statusCn(revision.status))}</span></div>
        <div class="history-row-meta">${escapeHtml(revision.updatedAt || revision.createdAt || "")}</div>`;
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
      const shell = fieldShell(variableLabel(key));
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
  // 对话（普通对话 / 智能问答）
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
  const CUSTOMER_SERVICE_INTENT_PROFILE = {
    id: "customer_service_intent",
    intents: [
      "order_policy", "order_query", "need_order_id", "product_consult",
      "complaint", "human_transfer", "bug_feedback", "sales_lead", "chitchat"
    ],
    outputSchema: {
      type: "object",
      required: ["intent", "hasOrderId", "needsOrderId", "orderIds", "confidence"],
      additionalProperties: false,
      properties: {
        intent: {
          type: "string",
          title: "意图",
          enum: [
            "order_policy", "order_query", "need_order_id", "product_consult",
            "complaint", "human_transfer", "bug_feedback", "sales_lead", "chitchat"
          ]
        },
        hasOrderId: { type: "boolean", title: "是否已有订单号" },
        needsOrderId: { type: "boolean", title: "是否需要补充订单号" },
        orderIds: { type: "array", title: "订单号列表", items: { type: "string" } },
        confidence: { type: "number", title: "置信度" }
      }
    },
    writeState: {
      intent: "{{lastOutput.parsed.intent}}",
      hasOrderId: "{{lastOutput.parsed.hasOrderId}}",
      needsOrderId: "{{lastOutput.parsed.needsOrderId}}",
      orderIds: "{{lastOutput.parsed.orderIds}}",
      confidence: "{{lastOutput.parsed.confidence}}"
    }
  };

  function buildWorkflowDefinition() {
    applyAutomaticStructuredOutputContracts();
    return {
      nodes: state.nodes.map((node) => {
        const payload = { id: node.id, type: node.type, config: cleanConfig(node.config) };
        if (cleanText(node.label)) payload.label = cleanText(node.label);
        if (cleanText(node.route)) payload.route = cleanText(node.route);
        return payload;
      }),
      edges: state.edges.map((edge) => {
        const payload = { from: edge.from, to: edge.to };
        if (edge.condition) payload.condition = edge.condition;
        if (cleanText(edge.label)) payload.label = cleanText(edge.label);
        if (cleanText(edge.route)) payload.route = cleanText(edge.route);
        return payload;
      })
    };
  }

  function applyAutomaticStructuredOutputContracts() {
    state.nodes
      .filter((node) => node.type === "llm")
      .forEach((node) => {
        const config = node.config || (node.config = {});
        if (hasManualStructuredOutputContract(config)) return;
        const profile = inferStructuredOutputProfile(node);
        if (!profile) return;
        const enumValues = mergeUnique(profile.intents, conditionValuesForNodeField(node.id, "intent"));
        const schema = cloneStructuredValue(profile.outputSchema);
        schema.properties.intent.enum = enumValues;
        const existingWriteState = config.writeState && typeof config.writeState === "object" && !Array.isArray(config.writeState)
          ? config.writeState
          : {};
        config.outputMode = "json";
        config.outputSchema = cloneStructuredValue(schema);
        config.writeState = { ...profile.writeState, ...existingWriteState };
        config.prompt = promptWithStructuredOutputInstruction(config.prompt || "");
        config.autoStructuredOutputContract = profile.id;
      });
  }

  function inferStructuredOutputProfile(node) {
    const intentValues = conditionValuesForNodeField(node.id, "intent");
    if (intentValues.some((value) => CUSTOMER_SERVICE_INTENT_PROFILE.intents.includes(value))) {
      return CUSTOMER_SERVICE_INTENT_PROFILE;
    }
    const text = [
      node.id, node.label, node.route,
      node.config?.prompt
    ].map((value) => String(value || "").toLowerCase()).join(" ");
    const router = ["意图", "intent", "路由", "分流", "分类", "判断", "route", "classif"]
      .some((word) => text.includes(word));
    const service = ["客服", "订单", "商品", "政策", "物流", "退款", "退货", "customer", "order", "product", "policy"]
      .some((word) => text.includes(word));
    return router && service ? CUSTOMER_SERVICE_INTENT_PROFILE : null;
  }

  function hasManualStructuredOutputContract(config) {
    const schema = config.outputSchema;
    return schema && typeof schema === "object" && !Array.isArray(schema)
      && Object.keys(schema).length > 0
      && config.autoStructuredOutputContract !== CUSTOMER_SERVICE_INTENT_PROFILE.id
      && !isCustomerServiceIntentSchema(schema);
  }

  function isCustomerServiceIntentSchema(schema) {
    let parsed = schema;
    if (typeof parsed === "string") {
      try { parsed = JSON.parse(parsed || "{}"); } catch (error) { parsed = {}; }
    }
    const properties = parsed && typeof parsed === "object" && !Array.isArray(parsed)
      && parsed.properties && typeof parsed.properties === "object" && !Array.isArray(parsed.properties)
      ? parsed.properties
      : {};
    const fields = new Set(Object.keys(properties));
    const matchedFields = ["intent", "hasOrderId", "needsOrderId", "orderIds", "confidence"]
      .filter((field) => fields.has(field));
    return fields.has("intent") && fields.has("confidence") && matchedFields.length >= 4;
  }

  function conditionValuesForNodeField(nodeId, fieldName) {
    const values = [];
    const marker = `{{nodes.${nodeId}.parsed.${fieldName}}}`;
    const inspect = (value) => {
      if (!value) return;
      if (Array.isArray(value)) { value.forEach(inspect); return; }
      if (typeof value === "object") {
        if (String(value.left || "").replace(/\s+/g, "") === marker) {
          const right = cleanText(value.right);
          if (right) values.push(right);
        }
        Object.values(value).forEach(inspect);
      }
    };
    state.nodes.filter((node) => node.type === "condition").forEach((node) => inspect(node.config));
    return mergeUnique(values, []);
  }

  function promptWithStructuredOutputInstruction(prompt) {
    const base = cleanText(prompt) || "请根据用户消息识别客服意图：{{input.message}}";
    if (base.includes("必须只输出一个 JSON 对象")) return base;
    return `${base}

结构化输出要求：
必须只输出一个 JSON 对象，不要 Markdown，不要解释，不要多余文本。JSON 字段必须为：
{
  "intent": "order_policy | order_query | need_order_id | product_consult | complaint | human_transfer | bug_feedback | sales_lead | chitchat",
  "hasOrderId": true/false,
  "needsOrderId": true/false,
  "orderIds": ["识别到的订单号，没有则为空数组"],
  "confidence": 0.0 到 1.0
}`;
  }

  function mergeUnique(first, second) {
    return Array.from(new Set([...(first || []), ...(second || [])].filter(Boolean)));
  }

  function cloneStructuredValue(value) {
    return JSON.parse(JSON.stringify(value));
  }

  function cleanConfig(config) {
    const cleaned = {};
    Object.entries(config || {}).forEach(([key, value]) => {
      if (value === "" || value === null || value === undefined) return;
      cleaned[key] = value;
    });
    return cleaned;
  }

  function cleanText(value) {
    const text = String(value || "").trim();
    return text ? text : "";
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
