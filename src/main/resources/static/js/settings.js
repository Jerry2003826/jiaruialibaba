"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};


  // ============================================================
  // 设置（Settings）
  // ============================================================
  function bindSettings() {
    els.refreshSettings?.addEventListener("click", () => void loadSettings());
    els.saveTavilyKey?.addEventListener("click", () => void saveTavilyCredential());
    els.clearTavilyKey?.addEventListener("click", () => void clearTavilyCredential());
    els.httpCredentialType?.addEventListener("change", renderHttpCredentialFields);
    els.httpCredentialForm?.addEventListener("submit", (event) => {
      event.preventDefault();
      void createHttpCredential();
    });
    els.httpCredentialList?.addEventListener("click", (event) => {
      const button = event.target instanceof Element ? event.target.closest("[data-delete-http-credential]") : null;
      if (button) void deleteHttpCredential(button.dataset.deleteHttpCredential);
    });
    renderHttpCredentialFields();
  }

  async function loadSettings() {
    try {
      const health = await requestJson(API.health);
      if (els.settingsRuntime) {
        els.settingsRuntime.innerHTML = Object.entries(health || {})
          .map(([k, v]) => `<div class="data-item"><span>${escAppHtml(k)}</span><code>${escAppHtml(String(v))}</code></div>`)
          .join("") || '<div class="empty-state">无运行时信息</div>';
      }
    } catch (error) {
      if (els.settingsRuntime) els.settingsRuntime.innerHTML = `<div class="empty-state">${escAppHtml(error.message)}</div>`;
    }
    try {
      const servers = await requestJson(API.mcpServers);
      if (els.settingsMcp) {
        els.settingsMcp.innerHTML = (servers && servers.length)
          ? servers.map((s) => `<div class="data-item"><strong>${escAppHtml(s.name)}</strong><span class="muted">${escAppHtml((s.registeredTools || []).join(", "))}</span></div>`).join("")
          : '<div class="empty-state">未启用 MCP 服务</div>';
      }
    } catch (error) {
      if (els.settingsMcp) els.settingsMcp.innerHTML = `<div class="empty-state">${escAppHtml(error.message)}</div>`;
    }
    await loadTavilyCredentialStatus();
    await loadHttpCredentialsSettings();
    if (els.settingsSecurity) {
      els.settingsSecurity.innerHTML = [
        "生产请用 issuer 模式对接真实 IdP，禁用 dev-token",
        "API Key 明文仅创建时显示一次，仅可访问所属应用 runtime",
        "敏感信息经 SecretRedactor 脱敏，不入库/日志/前端",
        "详见 SECURITY.md / OPERATIONS.md"
      ].map((t) => `<div class="data-item"><span>${escAppHtml(t)}</span></div>`).join("");
    }
  }

  async function loadTavilyCredentialStatus() {
    try {
      renderTavilyCredentialStatus(await requestJson(API.tavilySettings));
    } catch (error) {
      if (els.settingsTavilyStatus) els.settingsTavilyStatus.textContent = "读取失败";
      if (els.settingsTavilyHint) els.settingsTavilyHint.textContent = error.message;
    }
  }

  function renderTavilyCredentialStatus(status) {
    const configured = Boolean(status?.configured);
    const source = status?.source === "environment" ? "环境变量" : status?.source === "runtime" ? "本次服务" : "未配置";
    if (els.settingsTavilyStatus) {
      els.settingsTavilyStatus.textContent = configured ? `已配置 · ${source}` : "未配置";
      els.settingsTavilyStatus.classList.toggle("badge-ok", configured);
    }
    if (els.clearTavilyKey) els.clearTavilyKey.disabled = status?.source !== "runtime";
    if (els.settingsTavilyHint) {
      els.settingsTavilyHint.textContent = status?.source === "environment"
        ? "当前使用服务端 TAVILY_API_KEY；前端不会读取或显示密钥。"
        : status?.source === "runtime"
          ? "当前密钥仅在本次服务进程中使用；重启后需重新填写，或配置 TAVILY_API_KEY。"
          : "填写 Tavily API Key 后才能运行 Tavily 搜索节点。";
    }
  }

  async function saveTavilyCredential() {
    const apiKey = els.settingsTavilyKey?.value.trim() || "";
    if (!apiKey) {
      toast("请填写 Tavily API Key", true);
      els.settingsTavilyKey?.focus();
      return;
    }
    els.saveTavilyKey.disabled = true;
    try {
      const status = await requestJson(API.tavilySettings, { method: "PUT", body: { apiKey } });
      els.settingsTavilyKey.value = "";
      renderTavilyCredentialStatus(status);
      toast("Tavily 密钥已配置");
    } catch (error) {
      toast(error.message, true);
    } finally {
      els.saveTavilyKey.disabled = false;
    }
  }

  async function clearTavilyCredential() {
    els.clearTavilyKey.disabled = true;
    try {
      const status = await requestJson(API.tavilySettings, { method: "DELETE" });
      renderTavilyCredentialStatus(status);
      toast("Tavily 临时密钥已清除");
    } catch (error) {
      toast(error.message, true);
      await loadTavilyCredentialStatus();
    }
  }

  function renderHttpCredentialFields() {
    const type = els.httpCredentialType?.value || "bearer";
    document.querySelectorAll("[data-http-credential-field]").forEach((field) => {
      field.classList.toggle("hidden", field.dataset.httpCredentialField !== type);
    });
  }

  async function loadHttpCredentialsSettings() {
    try {
      const credentials = await requestJson(API.httpCredentials);
      state.httpCredentials = Array.isArray(credentials) ? credentials : [];
      state.httpCredentialsLoaded = true;
      renderHttpCredentialList(state.httpCredentials);
    } catch (error) {
      if (els.httpCredentialList) {
        els.httpCredentialList.innerHTML = `<div class="empty-state">${escAppHtml(error.message)}</div>`;
      }
    }
  }

  function renderHttpCredentialList(credentials) {
    if (!els.httpCredentialList) return;
    if (!credentials.length) {
      els.httpCredentialList.innerHTML = '<div class="empty-state">暂无 HTTP 凭据</div>';
      return;
    }
    els.httpCredentialList.innerHTML = credentials.map((credential) => `
      <div class="data-item http-credential-item">
        <div>
          <strong>${escAppHtml(credential.name)}</strong>
          <div class="item-meta">${escAppHtml(httpCredentialSettingsTypeLabel(credential.type))} · ${escAppHtml(credential.credentialId)}</div>
        </div>
        <button class="icon-btn-sm" type="button" title="删除凭据" aria-label="删除凭据"
                data-delete-http-credential="${escAppHtml(credential.credentialId)}">×</button>
      </div>`).join("");
  }

  function httpCredentialSettingsTypeLabel(type) {
    return ({ bearer: "Bearer Token", api_key_header: "API Key Header", basic: "Basic Auth" })[type] || type;
  }

  async function createHttpCredential() {
    const name = els.httpCredentialName?.value.trim() || "";
    const type = els.httpCredentialType?.value || "bearer";
    if (!name) {
      toast("请填写凭据名称", true);
      els.httpCredentialName?.focus();
      return;
    }
    const payload = { name, type };
    if (type === "bearer") payload.token = els.httpCredentialToken?.value || "";
    if (type === "api_key_header") {
      payload.headerName = els.httpCredentialHeaderName?.value.trim() || "";
      payload.value = els.httpCredentialValue?.value || "";
    }
    if (type === "basic") {
      payload.username = els.httpCredentialUsername?.value || "";
      payload.password = els.httpCredentialPassword?.value || "";
    }
    els.saveHttpCredential.disabled = true;
    try {
      await requestJson(API.httpCredentials, { method: "POST", body: payload });
      resetHttpCredentialForm();
      await loadHttpCredentialsSettings();
      toast("HTTP 凭据已加密保存");
    } catch (error) {
      toast(error.message, true);
    } finally {
      els.saveHttpCredential.disabled = false;
    }
  }

  async function deleteHttpCredential(credentialId) {
    if (!credentialId) return;
    try {
      await requestJson(API.httpCredential(credentialId), { method: "DELETE" });
      await loadHttpCredentialsSettings();
      toast("已删除 HTTP 凭据");
    } catch (error) {
      toast(error.message, true);
    }
  }

  function resetHttpCredentialForm() {
    [els.httpCredentialName, els.httpCredentialToken, els.httpCredentialHeaderName,
      els.httpCredentialValue, els.httpCredentialUsername, els.httpCredentialPassword]
      .forEach((input) => { if (input) input.value = ""; });
  }

  AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || [];
  AgentWorkbench.loadedModules.push("settings");
  AgentWorkbench.bindSettings = bindSettings;
  AgentWorkbench.loadSettings = loadSettings;
