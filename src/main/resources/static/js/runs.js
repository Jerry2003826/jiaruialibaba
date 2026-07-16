"use strict";

window.AgentWorkbench = window.AgentWorkbench || {};


  async function runWorkflow() {
    syncFormToJson();
    const input = parseJsonInput(els.workflowInput.value, {});
    const payload = workflowRunPayload(input);
    const workflowDefinition = payload.workflowDefinition || runWorkflowDefinition();
    setRunStatus("运行中…");
    els.runResult.className = "result-card empty-result";
    els.runResult.textContent = "正在运行…";
    await runCommand({
      request: async () => {
        ensureRequiredWorkflowInputs(input);
        await ensureWorkflowCanRun(workflowDefinition);
        return requestJson(API.runWorkflow, { method: "POST", body: payload });
      },
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

  function workflowRunPayload(input) {
    if (state.definitionStatus === "PUBLISHED" && state.definitionId) {
      return {
        definitionId: state.definitionId,
        definitionVersion: state.definitionVersion,
        input
      };
    }
    return {
      workflowDefinition: runWorkflowDefinition(),
      input
    };
  }

  function setRunStatus(text) { if (els.runHandleStatus) els.runHandleStatus.textContent = text; }

  // 友好结果：从响应中提取可读答案
  function renderRunResult(response, options = {}) {
    const answer = deepFindText(response);
    const artifactGroups = findReportArtifactGroups(response);
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
    if (artifactGroups.length > 0) renderReportArtifacts(els.runResult, artifactGroups);
    if (response.runId && options.hydrateArtifacts !== false) {
      void hydrateReportArtifacts(els.runResult, response.runId);
    }
  }

  function findReportArtifactGroups(value, depth = 0, seen = new Set()) {
    if (depth > 8 || value == null || typeof value !== "object" || seen.has(value)) return [];
    seen.add(value);
    if (Array.isArray(value.artifacts) && value.exportId) {
      return [{
        exportId: value.exportId,
        artifacts: value.artifacts,
        primary: value.primary || value.artifacts[0] || null,
        printPreview: value.printPreview || null,
        expiresAt: value.expiresAt || value.artifacts[0]?.expiresAt || null
      }];
    }
    return Object.values(value).flatMap((item) => findReportArtifactGroups(item, depth + 1, seen));
  }

  async function hydrateReportArtifacts(container, runId) {
    try {
      const groups = await requestJson(API.workflowRunArtifacts(runId));
      if (Array.isArray(groups) && groups.length > 0) renderReportArtifacts(container, groups);
    } catch (error) {
      const existing = container.querySelector(".report-artifacts");
      if (!existing) return;
      const warning = document.createElement("div");
      warning.className = "report-artifact-warning";
      warning.textContent = `刷新报告列表失败：${error.message}`;
      existing.appendChild(warning);
    }
  }

  function renderReportArtifacts(container, groups) {
    container.querySelector(".report-artifacts")?.remove();
    const section = document.createElement("section");
    section.className = "report-artifacts";
    const heading = document.createElement("div");
    heading.className = "report-artifacts-heading";
    heading.textContent = "生成的报告";
    section.appendChild(heading);
    groups.forEach((group) => {
      const block = document.createElement("div");
      block.className = "report-artifact-group";
      (Array.isArray(group.artifacts) ? group.artifacts : []).forEach((artifact) => {
        block.appendChild(reportArtifactRow(artifact));
      });
      if (group.printPreview?.contentUrl || group.printPreview?.downloadUrl) {
        const print = document.createElement("button");
        print.type = "button";
        print.className = "btn btn-sm btn-ghost report-print-button";
        print.textContent = "打印";
        print.addEventListener("click", () => void printReportArtifact(group.printPreview, print));
        block.appendChild(print);
      }
      const expiry = group.expiresAt || group.artifacts?.[0]?.expiresAt;
      if (expiry) {
        const meta = document.createElement("div");
        meta.className = "report-artifact-expiry";
        meta.textContent = `保留至 ${formatArtifactDate(expiry)}`;
        block.appendChild(meta);
      }
      section.appendChild(block);
    });
    container.appendChild(section);
  }

  function reportArtifactRow(artifact) {
    const row = document.createElement("div");
    row.className = "report-artifact-row";
    const details = document.createElement("div");
    details.className = "report-artifact-details";
    const name = document.createElement("strong");
    name.textContent = artifact.fileName || `报告.${artifact.format || "file"}`;
    const meta = document.createElement("span");
    meta.textContent = `${String(artifact.format || "").toUpperCase()} · ${formatArtifactSize(artifact.sizeBytes)}`;
    details.append(name, meta);
    const download = document.createElement("button");
    download.type = "button";
    download.className = "btn btn-sm btn-secondary";
    download.textContent = "下载";
    download.disabled = !artifact.downloadUrl;
    download.addEventListener("click", () => void downloadReportArtifact(artifact, download));
    row.append(details, download);
    return row;
  }

  async function requestArtifactBlob(url) {
    const response = await fetch(url, { headers: authHeaders({ Accept: "*/*" }), cache: "no-store" });
    if (response.ok) return response.blob();
    let message = `HTTP ${response.status}`;
    try {
      const payload = await response.json();
      message = payload?.message || payload?.code || message;
    } catch (error) { /* preserve the status fallback */ }
    throw new Error(message);
  }

  async function downloadReportArtifact(artifact, button) {
    button.disabled = true;
    try {
      const blob = await requestArtifactBlob(artifact.contentUrl || artifact.downloadUrl);
      const objectUrl = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = objectUrl;
      link.download = artifact.fileName || `report.${artifact.format || "bin"}`;
      document.body.appendChild(link);
      link.click();
      link.remove();
      setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (error) {
      toast(`下载失败：${error.message}`, true);
    } finally {
      button.disabled = false;
    }
  }

  async function printReportArtifact(artifact, button) {
    button.disabled = true;
    try {
      const blob = await requestArtifactBlob(artifact.contentUrl || artifact.downloadUrl);
      const objectUrl = URL.createObjectURL(blob);
      const frame = document.createElement("iframe");
      frame.className = "report-print-frame";
      frame.title = "报告打印预览";
      const cleanup = () => {
        frame.remove();
        URL.revokeObjectURL(objectUrl);
      };
      frame.addEventListener("load", () => {
        const printWindow = frame.contentWindow;
        if (!printWindow) { cleanup(); return; }
        printWindow.addEventListener("afterprint", cleanup, { once: true });
        printWindow.focus();
        printWindow.print();
        setTimeout(cleanup, 60000);
      }, { once: true });
      frame.src = objectUrl;
      document.body.appendChild(frame);
    } catch (error) {
      toast(`打印预览失败：${error.message}`, true);
    } finally {
      button.disabled = false;
    }
  }

  function formatArtifactSize(value) {
    const bytes = Number(value || 0);
    if (bytes < 1024) return `${bytes} B`;
    if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KiB`;
    return `${(bytes / (1024 * 1024)).toFixed(1)} MiB`;
  }

  function formatArtifactDate(value) {
    const date = new Date(value);
    return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString("zh-CN", { hour12: false });
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
    const [detailResult, artifactsResult] = await Promise.allSettled([
      requestJson(API.workflowRunDetail(run.runId)),
      requestJson(API.workflowRunArtifacts(run.runId))
    ]);
    if (detailResult.status === "fulfilled" && detailResult.value?.run) {
      renderRunResult(detailResult.value.run, { hydrateArtifacts: false });
    } else {
      els.runResult.className = "result-card";
      els.runResult.innerHTML = `<div class="result-label">结果</div><div class="result-answer">历史运行详情暂时无法恢复。</div>`;
    }
    if (artifactsResult.status === "fulfilled" && Array.isArray(artifactsResult.value)
        && artifactsResult.value.length > 0) {
      renderReportArtifacts(els.runResult, artifactsResult.value);
    }
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

  function normalizeWorkflowVariableSchema(schema) {
    const normalizeInput = (variable) => ({
      name: cleanText(variable?.name),
      type: cleanText(variable?.type) || "string",
      required: Boolean(variable?.required),
      defaultValue: variable?.defaultValue ?? null,
      description: cleanText(variable?.description)
    });
    return {
      inputs: (Array.isArray(schema?.inputs) ? schema.inputs : [])
        .map(normalizeInput)
        .filter((variable) => variable.name),
      outputs: Array.isArray(schema?.outputs) ? cloneWorkflowValue(schema.outputs) : []
    };
  }

  function discoverWorkflowInputVariables() {
    const usages = new Map();
    const register = (path, searchQuery) => {
      const segments = cleanText(path).split(".").filter(Boolean);
      const name = segments[0] || "message";
      const existing = usages.get(name) || { nested: false, searchQuery: false };
      usages.set(name, {
        nested: existing.nested || segments.length > 1,
        searchQuery: existing.searchQuery || searchQuery
      });
    };
    const inspect = (value, tavilyNode, searchQuery = false) => {
      if (typeof value === "string") {
        for (const match of value.matchAll(/\{\{\s*input(?:\.([a-zA-Z][a-zA-Z0-9_.-]*))?\s*}}/g)) {
          register(match[1] || "message", tavilyNode && searchQuery);
        }
        return;
      }
      if (Array.isArray(value)) {
        value.forEach((child) => inspect(child, tavilyNode, searchQuery));
        return;
      }
      if (value && typeof value === "object") {
        Object.entries(value).forEach(([key, child]) => inspect(child, tavilyNode, searchQuery || key === "query"));
      }
    };
    state.nodes.forEach((node) => inspect(node.config, node.type === "tavily_search"));
    return Array.from(usages.entries()).map(([name, usage]) => ({
      name,
      type: inferredWorkflowInputType(name, usage.nested),
      required: true,
      defaultValue: null,
      description: usage.searchQuery || name === "topic" || name === "query"
        ? "输入要研究或搜索的主题"
        : name === "message" ? "输入给工作流的内容" : `工作流输入：${name}`
    }));
  }

  function inferredWorkflowInputType(name, nested) {
    if (nested) return "object";
    if (["amount", "count", "maxResults", "topK", "limit", "page", "pageSize"].includes(name)) return "number";
    if (["receiptProvided", "paid", "enabled", "includeAnswer", "includeRawContent"].includes(name)) return "boolean";
    if (["tools", "history", "orderIds", "items", "includeDomains", "excludeDomains"].includes(name)) return "array";
    return "string";
  }

  function effectiveWorkflowVariables() {
    const declared = normalizeWorkflowVariableSchema(state.workflowVariables);
    const inputs = new Map(declared.inputs.map((variable) => [variable.name, variable]));
    discoverWorkflowInputVariables().forEach((variable) => {
      if (!inputs.has(variable.name)) inputs.set(variable.name, variable);
    });
    if (inputs.size === 0) {
      Object.entries(currentInputObject()).forEach(([name, value]) => inputs.set(name, {
        name,
        type: Array.isArray(value) ? "array" : value === null ? "string" : typeof value,
        required: false,
        defaultValue: value,
        description: ""
      }));
    }
    return { inputs: Array.from(inputs.values()), outputs: declared.outputs };
  }

  function setWorkflowRunVariables(schema, options = {}) {
    state.workflowVariables = normalizeWorkflowVariableSchema(schema);
    syncWorkflowInputWithVariables(options);
    if (state.inputMode === "form") renderRunInputForm();
  }

  function syncWorkflowInputWithVariables(options = {}) {
    const preserveValues = options.preserveValues !== false;
    const previous = preserveValues ? currentInputObject() : {};
    const variables = effectiveWorkflowVariables().inputs;
    const next = {};
    variables.forEach((variable) => {
      if (preserveValues && Object.prototype.hasOwnProperty.call(previous, variable.name)) {
        next[variable.name] = previous[variable.name];
      } else if (variable.defaultValue !== null && variable.defaultValue !== undefined) {
        next[variable.name] = cloneWorkflowValue(variable.defaultValue);
      } else {
        next[variable.name] = emptyWorkflowInputValue(variable.type);
      }
    });
    if (els.workflowInput) els.workflowInput.value = formatJson(next);
  }

  function emptyWorkflowInputValue(type) {
    if (type === "object") return {};
    if (type === "array") return [];
    if (type === "boolean") return false;
    if (type === "number") return null;
    return "";
  }

  function inputVariablePresentation(variable) {
    const searchTopic = variable.description === "输入要研究或搜索的主题"
      || state.nodes.some((node) => node.type === "tavily_search"
        && templateReferencesInput(node.config?.query, variable.name));
    return {
      label: searchTopic ? "搜索主题" : variableLabel(variable.name),
      placeholder: searchTopic ? "输入你希望研究或搜索的主题" : variable.description
    };
  }

  function templateReferencesInput(value, name) {
    if (typeof value !== "string") return false;
    const escaped = String(name).replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
    return new RegExp(`\\{\\{\\s*input(?:\\.${escaped})?\\s*}}`).test(value)
      && (name === "message" || value.includes(`input.${name}`));
  }

  function renderRunInputForm() {
    const obj = currentInputObject();
    const variables = effectiveWorkflowVariables().inputs;
    els.runInputForm.innerHTML = "";
    variables.forEach((variable) => {
      const key = variable.name;
      const presentation = inputVariablePresentation(variable);
      const shell = fieldShell(`${presentation.label}${variable.required ? "（必填）" : ""}`);
      const value = obj[key] ?? emptyWorkflowInputValue(variable.type);
      let control;
      if (variable.type === "object" || variable.type === "array" || (value && typeof value === "object")) {
        control = document.createElement("textarea");
        control.className = "code-input";
        control.value = formatJson(value);
        control.dataset.json = "true";
      } else if (variable.type === "boolean") {
        control = document.createElement("input");
        control.type = "checkbox";
        control.checked = Boolean(value);
        control.dataset.boolean = "true";
      } else if (typeof value === "string" && (value.length > 40 || key === "message" || presentation.label === "搜索主题")) {
        control = document.createElement("textarea");
        control.className = "text-area";
        control.value = value;
      } else if (variable.type === "number" || typeof value === "number") {
        control = document.createElement("input");
        control.className = "text-input"; control.type = "number"; control.value = value;
        control.dataset.num = "true";
      } else {
        control = document.createElement("input");
        control.className = "text-input"; control.type = "text"; control.value = value ?? "";
      }
      control.dataset.key = key;
      if (presentation.placeholder) control.placeholder = presentation.placeholder;
      control.required = Boolean(variable.required);
      control.addEventListener("input", syncFormToJson);
      control.addEventListener("change", syncFormToJson);
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
      } else if (control.dataset.boolean === "true") {
        obj[key] = control.checked;
      } else if (control.dataset.num === "true") {
        obj[key] = control.value === "" ? null : Number(control.value);
      } else {
        obj[key] = control.value;
      }
    });
    els.workflowInput.value = formatJson(obj);
  }

  function ensureRequiredWorkflowInputs(input) {
    const missing = effectiveWorkflowVariables().inputs
      .filter((variable) => variable.required)
      .filter((variable) => {
        const value = input?.[variable.name];
        return value === null || value === undefined || (typeof value === "string" && !value.trim());
      });
    if (missing.length === 0) return;
    const labels = missing.map((variable) => inputVariablePresentation(variable).label).join("、");
    throw new Error(`请先填写${labels}`);
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
    const artifactsHost = document.createElement("div");
    artifactsHost.className = "run-history-artifacts";
    els.runDetail.append(artifactsHost, stepsHost);
    try {
      const [steps, graph, artifacts] = await Promise.allSettled([
        requestJson(API.runSteps(run.runId)),
        requestJson(API.workflowRunGraph(run.runId)),
        requestJson(API.workflowRunArtifacts(run.runId))
      ]);
      if (artifacts.status === "fulfilled" && Array.isArray(artifacts.value) && artifacts.value.length > 0) {
        renderReportArtifacts(artifactsHost, artifacts.value);
      }
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

  function runWorkflowDefinition() {
    return buildWorkflowDefinition();
  }

  async function ensureWorkflowCanRun(workflowDefinition) {
    const response = await requestJson(API.validateWorkflow, {
      method: "POST",
      body: { workflowDefinition }
    });
    if (response?.valid !== false) return response;
    const detail = formatWorkflowValidationErrors(response.errors);
    throw new Error(detail ? `工作流未通过校验，不能运行：${detail}` : "工作流未通过校验，不能运行");
  }

  function formatWorkflowValidationErrors(errors) {
    if (!Array.isArray(errors) || errors.length === 0) return "";
    return errors
      .map((error) => cleanText(error?.message || error?.code || error))
      .filter(Boolean)
      .join("；");
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
      node.id, node.label,
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
