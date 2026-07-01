"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};


  // ============================================================
  // API 端点（后端契约，保持不变）
  // ============================================================
  const API = {
    health: "/api/health",
    devToken: "/api/auth/dev-token",
    nodeSchemas: "/api/workflows/node-schemas",
    generateWorkflow: "/api/workflows/generate",
    generateWorkflowStream: "/api/workflows/generate/stream",
    validateWorkflow: "/api/workflows/validate",
    previewGraph: "/api/workflows/preview-graph",
    runWorkflow: "/api/workflows/run",
    definitions: "/api/workflows/definitions",
    definitionRevisions: (id) => `/api/workflows/definitions/${encodeURIComponent(id)}/revisions`,
    rollbackDefinition: (id, version) => `/api/workflows/definitions/${encodeURIComponent(id)}/rollback/${version}`,
    workflowRuns: (id) => `/api/workflows/runs?definitionId=${encodeURIComponent(id)}&page=0&size=20`,
    publishDefinition: (id) => `/api/workflows/definitions/${encodeURIComponent(id)}/publish`,
    workflowRunGraph: (runId) => `/api/workflows/runs/${encodeURIComponent(runId)}/graph`,
    workflowRunEvents: (runId) => `/api/workflows/runs/${encodeURIComponent(runId)}/events`,
    cancelWorkflowRun: (runId) => `/api/workflows/runs/${encodeURIComponent(runId)}/cancel`,
    runSteps: (runId) => `/api/runs/${encodeURIComponent(runId)}/steps`,
    runs: "/api/runs",
    tools: "/api/tools",
    mcpServers: "/api/tools/mcp/servers",
    chat: "/api/chat",
    chatStream: "/api/chat/stream",
    assistantChat: "/api/agent/assistant-chat",
    clearConversation: (id) => `/api/chat/conversations/${encodeURIComponent(id)}`,
    saveDocument: "/api/rag/documents",
    updateDocument: (id) => `/api/rag/documents/${encodeURIComponent(id)}`,
    listDocuments: "/api/rag/documents",
    deleteDocument: (id) => `/api/rag/documents/${encodeURIComponent(id)}`,
    listOrders: "/api/orders",
    saveOrderEndpoint: "/api/orders",
    orderDetail: (id) => `/api/orders/${encodeURIComponent(id)}`,
    apps: "/api/apps",
    appDetail: (id) => `/api/apps/${encodeURIComponent(id)}`,
    publishApp: (id) => `/api/apps/${encodeURIComponent(id)}/publish`,
    appRun: (id) => `/api/apps/${encodeURIComponent(id)}/run`,
    appChat: (id) => `/api/apps/${encodeURIComponent(id)}/chat`,
    appApiKeys: (id) => `/api/apps/${encodeURIComponent(id)}/api-keys`,
    revokeAppApiKey: (id, keyId) => `/api/apps/${encodeURIComponent(id)}/api-keys/${encodeURIComponent(keyId)}`,
    runUsage: (runId) => `/api/runs/${encodeURIComponent(runId)}/usage`,
    knowledgeBases: "/api/knowledge-bases",
    kbTextDoc: (kbId) => `/api/knowledge-bases/${encodeURIComponent(kbId)}/documents/text`,
    kbFileDoc: (kbId) => `/api/knowledge-bases/${encodeURIComponent(kbId)}/documents/files`,
    kbDocs: (kbId) => `/api/knowledge-bases/${encodeURIComponent(kbId)}/documents`,
    kbSearch: (kbId) => `/api/knowledge-bases/${encodeURIComponent(kbId)}/search`
  };

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
      const raw = dataLines.join("\n");
      // Only the JSON parse may fall back to a raw payload. The onEvent handler must run exactly
      // once and outside this try, otherwise a handler that throws (e.g. on an "error" event) gets
      // caught here and re-dispatched with the raw JSON string instead of surfacing cleanly.
      let payload;
      try { payload = JSON.parse(raw); }
      catch (error) { payload = { message: raw }; }
      onEvent(eventName, payload);
    });
    return remainder;
  }

  AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || [];
  AgentWorkbench.loadedModules.push("api");
  AgentWorkbench.helpers = AgentWorkbench.helpers || {};
  AgentWorkbench.API = API;
  AgentWorkbench.requestJson = requestJson;
  AgentWorkbench.consumeSse = consumeSse;
  AgentWorkbench.helpers.requestJson = requestJson;
  AgentWorkbench.helpers.consumeSse = consumeSse;
  window.AgentWorkbench.API = API;
  window.AgentWorkbench.requestJson = requestJson;
  window.AgentWorkbench.consumeSse = consumeSse;
