"use strict";

window.AgentWorkbench = window.AgentWorkbench || {};


  // 应用（Apps）与 API Key —— Dify-like 产品台
  // ============================================================
  const appsState = { selectedAppId: null, selectedType: null };

  function bindApps() {
    els.refreshApps?.addEventListener("click", () => void loadApps());
    els.createApp?.addEventListener("click", () => void createApp());
    els.createApiKey?.addEventListener("click", () => void createApiKey());
    els.appRun?.addEventListener("click", () => void runSelectedApp());
    els.createAppType?.addEventListener("change", () => syncCreateAppFields());
    syncCreateAppFields();
    els.appsList?.addEventListener("click", (event) => {
      const card = event.target.closest("[data-app-id]");
      if (!card) return;
      const action = event.target.closest("[data-app-action]")?.dataset.appAction;
      if (action === "publish") void publishApp(card.dataset.appId);
      else if (action === "delete") void deleteApp(card.dataset.appId);
      else void selectApp(card.dataset.appId);
    });
    els.appApiKeys?.addEventListener("click", (event) => {
      const btn = event.target.closest("[data-key-revoke]");
      if (btn) void revokeApiKey(appsState.selectedAppId, btn.dataset.keyRevoke);
    });
  }

  function syncCreateAppFields() {
    const type = els.createAppType?.value || "CHAT";
    document.querySelectorAll("[data-app-field]").forEach((field) => {
      const show = field.dataset.appField === "workflow" ? type === "WORKFLOW" : type !== "WORKFLOW";
      field.classList.toggle("hidden", !show);
    });
  }

  async function loadApps() {
    if (!els.appsList) return;
    try {
      const page = await requestJson(`${API.apps}?page=0&size=50`);
      const apps = page?.content || [];
      els.appsList.innerHTML = apps.length ? "" : '<div class="empty-state">还没有应用，先在左侧创建。</div>';
      apps.forEach((app) => els.appsList.appendChild(renderAppCard(app)));
    } catch (error) { toast(error.message, true); }
  }

  function renderAppCard(app) {
    const card = document.createElement("div");
    card.className = "data-item";
    card.dataset.appId = app.appId;
    card.innerHTML = `
      <div class="data-item-main">
        <strong>${escAppHtml(app.name)}</strong>
        <span class="badge badge-soft">${escAppHtml(app.type)}</span>
        <span class="badge ${app.status === "PUBLISHED" ? "badge-ok" : "badge-draft"}">${escAppHtml(app.status)}</span>
        <span class="muted">v${app.version}${app.publishedVersion ? " · 已发布 v" + app.publishedVersion : ""}</span>
      </div>
      <div class="data-item-actions">
        <button class="btn btn-ghost btn-sm" type="button" data-app-action="open">详情</button>
        <button class="btn btn-ghost btn-sm" type="button" data-app-action="publish">发布</button>
        <button class="btn btn-danger btn-sm" type="button" data-app-action="delete">删除</button>
      </div>`;
    return card;
  }

  async function createApp() {
    const name = (els.createAppName?.value || "").trim();
    if (!name) { toast("请输入应用名称", true); return; }
    const type = els.createAppType?.value || "CHAT";
    const body = { name, type };
    if (type === "WORKFLOW") {
      body.workflowDefinitionId = (els.createAppWorkflowId?.value || "").trim();
    } else {
      const kbIds = (els.createAppKbIds?.value || "").split(",").map((s) => s.trim()).filter(Boolean);
      body.config = {
        systemPrompt: (els.createAppSystemPrompt?.value || "").trim() || null,
        model: (els.createAppModel?.value || "").trim() || null,
        knowledgeBaseIds: kbIds.length ? kbIds : null
      };
    }
    try {
      const app = await requestJson(API.apps, { method: "POST", body });
      toast(`已创建应用「${app.name}」`);
      if (els.createAppName) els.createAppName.value = "";
      await loadApps();
      await selectApp(app.appId);
    } catch (error) { toast(error.message, true); }
  }

  async function publishApp(appId) {
    try {
      const app = await requestJson(API.publishApp(appId), { method: "POST" });
      toast(`已发布「${app.name}」`);
      await loadApps();
      if (appsState.selectedAppId === appId) await selectApp(appId);
    } catch (error) { toast(error.message, true); }
  }

  async function deleteApp(appId) {
    if (!window.confirm("确认删除该应用？有运行历史将改为归档。")) return;
    try {
      await requestJson(API.appDetail(appId), { method: "DELETE" });
      toast("已删除 / 归档应用");
      if (appsState.selectedAppId === appId && els.appDetail) { appsState.selectedAppId = null; els.appDetail.hidden = true; }
      await loadApps();
    } catch (error) { toast(error.message, true); }
  }

  async function selectApp(appId) {
    try {
      const app = await requestJson(API.appDetail(appId));
      appsState.selectedAppId = app.appId;
      appsState.selectedType = app.type;
      if (els.appDetail) els.appDetail.hidden = false;
      if (els.appDetailTitle) els.appDetailTitle.textContent = `${app.name} · ${app.type}`;
      if (els.appDetailStatus) els.appDetailStatus.textContent = app.status;
      if (els.appRunInput) els.appRunInput.value = app.type === "WORKFLOW" ? '{"input":{"message":"你好"}}' : '{"message":"你好"}';
      if (els.appRunResult) { els.appRunResult.textContent = "发布后可测试 run/chat。"; els.appRunResult.classList.add("empty-result"); }
      if (els.apiKeyReveal) els.apiKeyReveal.classList.add("hidden");
      await loadApiKeys(appId);
    } catch (error) { toast(error.message, true); }
  }

  async function loadApiKeys(appId) {
    if (!els.appApiKeys) return;
    try {
      const keys = await requestJson(API.appApiKeys(appId));
      els.appApiKeys.innerHTML = (keys && keys.length) ? "" : '<div class="empty-state">还没有 API Key。</div>';
      (keys || []).forEach((key) => {
        const item = document.createElement("div");
        item.className = "data-item";
        item.innerHTML = `
          <div class="data-item-main">
            <code>${escAppHtml(key.keyId)}</code>
            <span class="badge ${key.status === "ACTIVE" ? "badge-ok" : "badge-draft"}">${escAppHtml(key.status)}</span>
            <span class="muted">${escAppHtml(key.name || "")}</span>
          </div>
          <div class="data-item-actions">
            <button class="btn btn-danger btn-sm" type="button" data-key-revoke="${escAppHtml(key.keyId)}">撤销</button>
          </div>`;
        els.appApiKeys.appendChild(item);
      });
      if (els.appCurl) {
        els.appCurl.textContent = `curl -X POST ${location.origin}${API.appChat(appId)} \\\n  -H "X-App-API-Key: app_xxx" -H 'Content-Type: application/json' \\\n  -d '{"message":"你好"}'`;
      }
    } catch (error) { toast(error.message, true); }
  }

  async function createApiKey() {
    const appId = appsState.selectedAppId;
    if (!appId) { toast("请先选择一个应用", true); return; }
    try {
      const created = await requestJson(API.appApiKeys(appId), { method: "POST", body: { name: "console" } });
      if (els.apiKeyReveal) {
        els.apiKeyReveal.classList.remove("hidden");
        els.apiKeyReveal.innerHTML = `明文密钥仅显示一次，请立即复制：<code>${escAppHtml(created.plaintextKey)}</code>`;
      }
      toast("已创建 API Key（明文仅显示一次）");
      await loadApiKeys(appId);
    } catch (error) { toast(error.message, true); }
  }

  async function revokeApiKey(appId, keyId) {
    if (!appId || !keyId) return;
    if (!window.confirm(`撤销 API Key ${keyId}？`)) return;
    try {
      await requestJson(API.revokeAppApiKey(appId, keyId), { method: "DELETE" });
      toast("已撤销 API Key");
      await loadApiKeys(appId);
    } catch (error) { toast(error.message, true); }
  }

  async function runSelectedApp() {
    const appId = appsState.selectedAppId;
    if (!appId) { toast("请先选择一个应用", true); return; }
    let body;
    try { body = JSON.parse(els.appRunInput?.value || "{}"); }
    catch (error) { toast("输入不是合法 JSON", true); return; }
    const isWorkflow = appsState.selectedType === "WORKFLOW";
    try {
      const result = await requestJson(isWorkflow ? API.appRun(appId) : API.appChat(appId), { method: "POST", body });
      if (els.appRunResult) {
        els.appRunResult.classList.remove("empty-result");
        const answer = isWorkflow ? JSON.stringify(result.output) : result.answer;
        els.appRunResult.innerHTML = `<div>${escAppHtml(String(answer ?? ""))}</div><div class="muted">runId: ${escAppHtml(result.runId || "")}</div>`;
      }
    } catch (error) { toast(error.message, true); }
  }
