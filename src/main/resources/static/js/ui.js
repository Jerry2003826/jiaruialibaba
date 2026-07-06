"use strict";

window.AgentWorkbench = window.AgentWorkbench || {};


  // ============================================================
  // 元素缓存
  // ============================================================
  function cacheElements() {
    const ids = [
      "runtime-chip", "runtime-dot", "runtime-status", "runtime-details",
      "definition-name", "workflow-status", "definition-select", "load-definition", "toggle-history",
      "save-definition", "run-workflow", "wf-more", "wf-menu", "new-workflow", "generate-workflow", "validate-workflow",
      "insert-loop-template", "publish-definition",
      "palette", "palette-collapse", "node-palette",
      "workflow-canvas", "canvas-world", "edge-layer", "node-layer", "canvas-empty",
      "route-map-panel", "route-map-title", "route-map-toggle", "route-map-list",
      "zoom-out", "zoom-label", "zoom-in", "zoom-fit",
      "node-inspector", "inspector-title", "inspector-close", "inspector-empty", "inspector-form",
      "edge-section", "add-edge", "edge-list", "delete-node",
      "definition-history", "refresh-definition-history", "history-close", "revision-list", "workflow-run-list",
      "workflow-generator", "generator-close", "generator-prompt", "generator-preview", "generator-apply",
      "run-drawer", "run-drawer-toggle", "run-handle-status", "input-mode-seg", "run-input-form",
      "workflow-input", "run-workflow-drawer", "refresh-run-graph", "run-result", "trace-steps", "run-output",
      "chat-mode-pill", "chat-mode-seg", "clear-chat", "chat-transcript", "chat-hint", "chat-message", "stream-chat", "send-chat",
      "refresh-documents", "rag-hint", "document-title", "document-content", "save-document", "reset-document-editor",
      "document-list", "refresh-orders", "order-id", "order-customer-name", "order-status", "order-amount",
      "order-currency", "order-estimated-delivery", "order-paid", "order-carrier", "order-tracking-number",
      "order-latest-event", "order-next-action", "save-order", "reset-order-editor", "order-list",
      "refresh-runs", "run-list", "run-detail",
      "refresh-tools", "tool-list", "mcp-server-list", "toast",
      "refresh-apps", "create-app-name", "create-app-type", "create-app-workflow-id",
      "create-app-system-prompt", "create-app-model", "create-app-kb-ids", "create-app", "apps-list",
      "app-detail", "app-detail-title", "app-detail-status", "app-run-input", "app-run",
      "app-run-result", "create-api-key", "api-key-reveal", "app-api-keys", "app-curl",
      "refresh-settings", "settings-runtime", "settings-mcp", "settings-security",
      "refresh-kb", "create-kb-name", "create-kb", "kb-list", "kb-detail", "kb-detail-title",
      "kb-doc-title", "kb-doc-content", "upload-kb-text", "kb-file", "upload-kb-file",
      "refresh-kb-docs", "kb-doc-list", "kb-search-query", "kb-search", "kb-search-results"
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
      ["工作流", `${runtimeLabel(health.workflowRuntime)} · 需发布：${boolCn(health.workflowRequirePublishedForRun)}`],
      ["严格模式", `${boolCn(health.strictMode)} · 回退：${boolCn(health.fallbackEnabled)}`],
      ["向量检索", `${boolCn(health.vectorStoreConfigured)} · ${retrieverLabel(health.ragRetriever)}`],
      ["已索引文档", `${health.indexedDocumentCount}`],
      ["MCP", boolCn(health.mcpEnabled)]
    ];
    els.runtimeDetails.innerHTML = rows.map(([k, v]) =>
      `<div class="rt-row"><span class="rt-key">${escapeHtml(k)}</span><span class="rt-val">${escapeHtml(String(v))}</span></div>`
    ).join("");
  }

  function runtimeLabel(value) {
    const text = String(value || "未知");
    if (/^graph$/i.test(text)) return "Graph";
    if (/^simple$/i.test(text)) return "Simple";
    return text;
  }

  function retrieverLabel(value) {
    const text = String(value || "未配置");
    if (/dashvector/i.test(text)) return "DashVector";
    if (/keyword/i.test(text)) return "Keyword";
    return text.replace(/DocumentRetriever$/i, "");
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

  AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || [];
  AgentWorkbench.loadedModules.push("ui");
  AgentWorkbench.cacheElements = cacheElements;
  AgentWorkbench.bindNavigation = bindNavigation;
  AgentWorkbench.bindRuntimeChip = bindRuntimeChip;
  AgentWorkbench.loadHealth = loadHealth;
  AgentWorkbench.runCommand = runCommand;
