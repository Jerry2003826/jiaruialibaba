"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};


  async function loadInitialData() {
    loadAssistantWorkflowBinding();
    await bootstrapAuth();
    await Promise.allSettled([loadHealth(), loadSchemas(), loadDefinitions(), loadToolCatalog()]);
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

  function loadAssistantWorkflowBinding() {
    try {
      const raw = window.localStorage?.getItem(ASSISTANT_WORKFLOW_BINDING_KEY);
      if (!raw) return;
      const binding = JSON.parse(raw);
      if (!binding?.definitionId) return;
      state.assistantWorkflowBound = true;
      state.assistantWorkflowUseCanvas = false;
      state.assistantWorkflowDefinitionId = binding.definitionId;
      state.assistantWorkflowDefinitionVersion = binding.version ?? null;
      state.assistantWorkflowDefinitionName = binding.name || "已绑定工作流";
      state.assistantWorkflowDefinitionStatus = binding.status || "PUBLISHED";
    } catch {
      window.localStorage?.removeItem(ASSISTANT_WORKFLOW_BINDING_KEY);
    }
  }

  function bindAssistantWorkflowFromDefinition(definition, options = {}) {
    if (!definition) return;
    state.assistantWorkflowBound = true;
    state.assistantWorkflowUseCanvas = options.useCanvas !== false;
    state.assistantWorkflowDefinitionId = definition.definitionId || null;
    state.assistantWorkflowDefinitionVersion = definition.version ?? null;
    state.assistantWorkflowDefinitionName = definition.name || els.definitionName?.value || "当前画布工作流";
    state.assistantWorkflowDefinitionStatus = definition.status || "DRAFT";
    if (state.assistantWorkflowDefinitionStatus === "PUBLISHED" && state.assistantWorkflowDefinitionId) {
      window.localStorage?.setItem(ASSISTANT_WORKFLOW_BINDING_KEY, JSON.stringify({
        definitionId: state.assistantWorkflowDefinitionId,
        version: state.assistantWorkflowDefinitionVersion,
        name: state.assistantWorkflowDefinitionName,
        status: state.assistantWorkflowDefinitionStatus
      }));
    }
    renderChat();
  }

  function assistantWorkflowPayload() {
    if (!state.assistantWorkflowBound) return {};
    if (state.assistantWorkflowDefinitionStatus === "PUBLISHED" && state.assistantWorkflowDefinitionId) {
      return workflowDefinitionReference(state.assistantWorkflowDefinitionId,
        state.assistantWorkflowDefinitionVersion);
    }
    if (state.assistantWorkflowUseCanvas) {
      if (state.definitionStatus === "PUBLISHED" && state.definitionId) {
        return workflowDefinitionReference(state.definitionId, state.definitionVersion);
      }
      return { workflowDefinition: buildWorkflowDefinition() };
    }
    if (state.assistantWorkflowDefinitionId) {
      return workflowDefinitionReference(state.assistantWorkflowDefinitionId,
        state.assistantWorkflowDefinitionVersion);
    }
    return {};
  }

  function workflowDefinitionReference(definitionId, version) {
    const payload = { workflowDefinitionId: definitionId };
    if (version != null) payload.workflowDefinitionVersion = version;
    return payload;
  }

  function assistantWorkflowLabel() {
    if (!state.assistantWorkflowBound) return "";
    const name = state.assistantWorkflowUseCanvas
      ? (els.definitionName?.value || state.assistantWorkflowDefinitionName || "当前画布工作流")
      : (state.assistantWorkflowDefinitionName || "已发布工作流");
    const version = state.definitionStatus === "PUBLISHED" && state.definitionVersion
      ? ` · v${state.definitionVersion}`
      : state.assistantWorkflowDefinitionVersion ? ` · v${state.assistantWorkflowDefinitionVersion}` : "";
    return `当前编排：${name}${version}`;
  }

  function authHeaders(extra = {}) {
    const headers = { ...extra };
    if (authToken) headers.Authorization = `Bearer ${authToken}`;
    return headers;
  }

  // ============================================================
  // 运行时健康
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
    els.clearChat?.addEventListener("click", () => void clearChatHistory());
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
    assistant: "自动结合知识库检索和工具调用，回答会附工具结果与引用来源。"
  };

  function renderChat() {
    if (!els.chatTranscript) return;
    const workflowLabel = state.chatMode === "assistant" ? assistantWorkflowLabel() : "";
    els.chatHint.textContent = [CHAT_HINTS[state.chatMode] || "", workflowLabel].filter(Boolean).join(" · ");
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

  function modeName(mode) { return { chat: "普通对话", assistant: "智能问答" }[mode] || "对话"; }

  function conversationIdForMode(mode = state.chatMode) {
    return mode === "assistant" ? "workbench-assistant" : "workbench";
  }

  async function clearChatHistory() {
    const mode = state.chatMode;
    const conversationId = conversationIdForMode(mode);
    if (els.clearChat) els.clearChat.disabled = true;
    try {
      await requestJson(API.clearConversation(conversationId), { method: "DELETE" });
      state.chatHistories[mode] = [];
      els.chatModePill.textContent = "已清空";
      renderChat();
      toast(`${modeName(mode)}记录已清空`);
    } catch (error) {
      toast(`清空失败：${error.message}`, true);
    } finally {
      if (els.clearChat) els.clearChat.disabled = false;
      window.setTimeout(() => {
        if (els.chatModePill?.textContent === "已清空") els.chatModePill.textContent = "就绪";
      }, 1200);
    }
  }

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
    const conversationId = conversationIdForMode();
    if (state.chatMode === "assistant") return requestJson(API.assistantChat, {
      method: "POST",
      body: { conversationId, message, ...assistantWorkflowPayload() }
    });
    return requestJson(API.chat, { method: "POST", body: { conversationId, message } });
  }

  function extrasForMode(response) {
    if (state.chatMode !== "assistant") return [];
    const extras = [];
    if (Array.isArray(response.toolCalls)) {
      extras.push(...response.toolCalls.map((call) => ({
        cls: call.succeeded ? "tool-ok" : "tool-err",
        title: `🔧 ${call.toolName || "tool"} · ${call.succeeded ? "成功" : "失败"}`,
        sub: `${call.provider || "local"}${call.remote ? " · 远程" : ""} · ${call.durationMs ?? 0}ms${call.errorMessage ? " · " + call.errorMessage : ""}`
      })));
    }
    if (Array.isArray(response.retrievedContext)) {
      extras.push(...response.retrievedContext.map((ctx) => ({
        cls: "",
        title: `📎 ${ctx.title || "文档 " + ctx.documentId}`,
        sub: `相关度 ${typeof ctx.score === "number" ? ctx.score.toFixed(3) : ctx.score} · ${truncate(ctx.snippet || "", 80)}`
      })));
    }
    return extras;
  }

  async function streamChatInto(assistant, message) {
    els.chatModePill.textContent = "流式中…";
    const parts = [];
    const response = await fetch(API.chatStream, {
      method: "POST",
      headers: authHeaders({ Accept: "text/event-stream", "Content-Type": "application/json" }),
      body: JSON.stringify({ conversationId: conversationIdForMode("chat"), message })
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
    els.resetDocumentEditor?.addEventListener("click", resetDocumentEditor);
    els.refreshDocuments?.addEventListener("click", () => void loadLibraryData());
    els.refreshOrders?.addEventListener("click", () => void loadOrders());
    els.saveOrder?.addEventListener("click", () => void saveOrder());
    els.resetOrderEditor?.addEventListener("click", resetOrderEditor);
  }

  async function loadLibraryData() {
    await Promise.allSettled([loadDocuments(), loadOrders()]);
  }

  async function saveDocument() {
    const editingId = state.editingDocumentId;
    await runCommand({
      request: () => requestJson(editingId ? API.updateDocument(editingId) : API.saveDocument, {
        method: editingId ? "PUT" : "POST",
        body: { title: els.documentTitle.value, content: els.documentContent.value }
      }),
      successToast: editingId ? "文档已更新" : "文档已保存",
      onSuccess: async () => {
        resetDocumentEditor();
        await Promise.allSettled([loadDocuments(), loadHealth()]);
      }
    });
  }

  function resetDocumentEditor() {
    state.editingDocumentId = null;
    if (els.documentTitle) els.documentTitle.value = "工作台笔记";
    if (els.documentContent) els.documentContent.value = "Spring AI Alibaba 在这个 demo 中支持智能体工作流、知识库 RAG、工具调用和运行轨迹查看。";
    if (els.saveDocument) els.saveDocument.textContent = "保存文档";
    els.resetDocumentEditor?.classList.add("hidden");
    highlightEditingDocument();
  }

  async function editDocument(id) {
    try {
      const detail = await requestJson(`${API.listDocuments}/${encodeURIComponent(id)}`);
      state.editingDocumentId = detail.id;
      if (els.documentTitle) els.documentTitle.value = detail.title || "";
      if (els.documentContent) els.documentContent.value = detail.content || "";
      if (els.saveDocument) els.saveDocument.textContent = "更新文档";
      els.resetDocumentEditor?.classList.remove("hidden");
      highlightEditingDocument();
      els.documentTitle?.focus();
    } catch (error) {
      toast(error.message, true);
    }
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
        item.dataset.documentId = String(document_.id);
        item.addEventListener("click", () => void editDocument(document_.id));
        const actions = document.createElement("div");
        actions.className = "doc-actions";
        const edit = document.createElement("button");
        edit.className = "icon-btn-sm"; edit.title = "编辑"; edit.setAttribute("aria-label", "编辑");
        edit.innerHTML = '<svg viewBox="0 0 24 24"><path d="M14 6a3 3 0 0 1 4 4l-8 8-4 1 1-4z"/><path d="M12 8l4 4"/></svg>';
        edit.addEventListener("click", (event) => { event.stopPropagation(); void editDocument(document_.id); });
        const del = document.createElement("button");
        del.className = "icon-btn-sm"; del.title = "删除"; del.setAttribute("aria-label", "删除");
        del.innerHTML = '<svg viewBox="0 0 24 24"><path d="M5 7h14M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>';
        del.addEventListener("click", (event) => { event.stopPropagation(); void deleteDocument(document_.id); });
        actions.append(edit, del);
        item.appendChild(actions);
        els.documentList.appendChild(item);
      });
      highlightEditingDocument();
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
      if (state.editingDocumentId === id) resetDocumentEditor();
      await Promise.allSettled([loadDocuments(), loadHealth()]);
    } catch (error) { toast(error.message, true); }
  }

  function highlightEditingDocument() {
    els.documentList?.querySelectorAll(".doc-item").forEach((item) => {
      item.classList.toggle("active", item.dataset.documentId === String(state.editingDocumentId));
    });
  }

  async function saveOrder() {
    const editingId = state.editingOrderId;
    const rawAmount = (els.orderAmount?.value || "").trim();
    if (rawAmount === "" || !Number.isFinite(Number(rawAmount))) {
      toast("请输入有效的订单金额（数字）", true);
      els.orderAmount?.focus();
      return;
    }
    const payload = orderPayloadFromForm();
    await runCommand({
      request: () => requestJson(editingId ? API.orderDetail(editingId) : API.saveOrderEndpoint, {
        method: editingId ? "PUT" : "POST",
        body: payload
      }),
      successToast: editingId ? "订单已更新" : "订单已保存",
      onSuccess: async () => {
        resetOrderEditor();
        await loadOrders();
      }
    });
  }

  function orderPayloadFromForm() {
    return {
      orderId: (els.orderId?.value || "").trim(),
      customerName: nullableText(els.orderCustomerName?.value),
      status: (els.orderStatus?.value || "").trim(),
      paid: Boolean(els.orderPaid?.checked),
      amount: Number((els.orderAmount?.value ?? "").trim()),
      currency: (els.orderCurrency?.value || "CNY").trim(),
      carrier: nullableText(els.orderCarrier?.value),
      trackingNumber: nullableText(els.orderTrackingNumber?.value),
      estimatedDelivery: nullableText(els.orderEstimatedDelivery?.value),
      latestEvent: nullableText(els.orderLatestEvent?.value),
      nextAction: nullableText(els.orderNextAction?.value)
    };
  }

  function resetOrderEditor() {
    state.editingOrderId = null;
    if (els.orderId) { els.orderId.value = ""; els.orderId.disabled = false; }
    if (els.orderCustomerName) els.orderCustomerName.value = "";
    if (els.orderStatus) els.orderStatus.value = "SHIPPED";
    if (els.orderPaid) els.orderPaid.checked = true;
    if (els.orderAmount) els.orderAmount.value = "";
    if (els.orderCurrency) els.orderCurrency.value = "CNY";
    if (els.orderEstimatedDelivery) els.orderEstimatedDelivery.value = "";
    if (els.orderCarrier) els.orderCarrier.value = "";
    if (els.orderTrackingNumber) els.orderTrackingNumber.value = "";
    if (els.orderLatestEvent) els.orderLatestEvent.value = "";
    if (els.orderNextAction) els.orderNextAction.value = "";
    if (els.saveOrder) els.saveOrder.textContent = "保存订单";
    els.resetOrderEditor?.classList.add("hidden");
    highlightEditingOrder();
  }

  async function editOrder(orderId) {
    try {
      const order = await requestJson(API.orderDetail(orderId));
      state.editingOrderId = order.orderId;
      if (els.orderId) { els.orderId.value = order.orderId || ""; els.orderId.disabled = true; }
      if (els.orderCustomerName) els.orderCustomerName.value = order.customerName || "";
      if (els.orderStatus) els.orderStatus.value = order.status || "";
      if (els.orderPaid) els.orderPaid.checked = Boolean(order.paid);
      if (els.orderAmount) els.orderAmount.value = order.amount ?? "";
      if (els.orderCurrency) els.orderCurrency.value = order.currency || "CNY";
      if (els.orderEstimatedDelivery) els.orderEstimatedDelivery.value = order.estimatedDelivery || "";
      if (els.orderCarrier) els.orderCarrier.value = order.carrier || "";
      if (els.orderTrackingNumber) els.orderTrackingNumber.value = order.trackingNumber || "";
      if (els.orderLatestEvent) els.orderLatestEvent.value = order.latestEvent || "";
      if (els.orderNextAction) els.orderNextAction.value = order.nextAction || "";
      if (els.saveOrder) els.saveOrder.textContent = "更新订单";
      els.resetOrderEditor?.classList.remove("hidden");
      highlightEditingOrder();
      els.orderCustomerName?.focus();
    } catch (error) {
      toast(error.message, true);
    }
  }

  async function loadOrders() {
    if (!els.orderList) return;
    try {
      const page = await requestJson(`${API.listOrders}?page=0&size=50`);
      const orders = page?.content || [];
      els.orderList.innerHTML = "";
      if (orders.length === 0) { els.orderList.appendChild(emptyDiv("暂无订单数据")); return; }
      orders.forEach((order) => {
        const item = document.createElement("div");
        item.className = "doc-item order-item";
        item.dataset.orderId = String(order.orderId);
        item.innerHTML = `<div><div class="doc-title">${escapeHtml(order.orderId || "")} · ${escapeHtml(order.customerName || "未命名客户")}</div>
          <div class="doc-meta">${escapeHtml(order.status || "UNKNOWN")} · ${escapeHtml(order.carrier || "未配置承运商")} · ${escapeHtml(order.trackingNumber || "无运单号")}</div>
          <div class="order-status-line"><span>${order.paid ? "已支付" : "未支付"}</span><span>${escapeHtml(order.amount ?? "")} ${escapeHtml(order.currency || "")}</span><span>${escapeHtml(order.estimatedDelivery || "未设置送达日")}</span></div></div>`;
        item.addEventListener("click", () => void editOrder(order.orderId));
        const actions = document.createElement("div");
        actions.className = "doc-actions";
        const edit = document.createElement("button");
        edit.className = "icon-btn-sm"; edit.title = "编辑"; edit.setAttribute("aria-label", "编辑订单");
        edit.innerHTML = '<svg viewBox="0 0 24 24"><path d="M14 6a3 3 0 0 1 4 4l-8 8-4 1 1-4z"/><path d="M12 8l4 4"/></svg>';
        edit.addEventListener("click", (event) => { event.stopPropagation(); void editOrder(order.orderId); });
        const del = document.createElement("button");
        del.className = "icon-btn-sm"; del.title = "删除"; del.setAttribute("aria-label", "删除订单");
        del.innerHTML = '<svg viewBox="0 0 24 24"><path d="M5 7h14M10 11v6M14 11v6M6 7l1 13h10l1-13M9 7V4h6v3"/></svg>';
        del.addEventListener("click", (event) => { event.stopPropagation(); void deleteOrder(order.orderId); });
        actions.append(edit, del);
        item.appendChild(actions);
        els.orderList.appendChild(item);
      });
      highlightEditingOrder();
    } catch (error) {
      els.orderList.innerHTML = "";
      els.orderList.appendChild(emptyDiv(`无法加载订单：${error.message}`));
    }
  }

  async function deleteOrder(orderId) {
    if (!window.confirm("删除该订单？")) return;
    try {
      await requestJson(API.orderDetail(orderId), { method: "DELETE" });
      toast("订单已删除");
      if (state.editingOrderId === orderId) resetOrderEditor();
      await loadOrders();
    } catch (error) { toast(error.message, true); }
  }

  function highlightEditingOrder() {
    els.orderList?.querySelectorAll(".order-item").forEach((item) => {
      item.classList.toggle("active", item.dataset.orderId === String(state.editingOrderId));
    });
  }

  // ============================================================
  // 运行记录
  // ============================================================

function bootstrapWorkbench() {
  AgentWorkbench.workflowController.init();
}

AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || [];
AgentWorkbench.loadedModules.push("main");
AgentWorkbench.bootstrapWorkbench = bootstrapWorkbench;
AgentWorkbench.init = bootstrapWorkbench;
window.AgentWorkbench.bootstrapWorkbench = bootstrapWorkbench;
window.AgentWorkbench.init = bootstrapWorkbench;

document.addEventListener("DOMContentLoaded", AgentWorkbench.bootstrapWorkbench);
