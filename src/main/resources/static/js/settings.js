"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};


  // ============================================================
  // 设置（Settings）
  // ============================================================
  function bindSettings() {
    els.refreshSettings?.addEventListener("click", () => void loadSettings());
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
    if (els.settingsSecurity) {
      els.settingsSecurity.innerHTML = [
        "生产请用 issuer 模式对接真实 IdP，禁用 dev-token",
        "API Key 明文仅创建时显示一次，仅可访问所属应用 runtime",
        "敏感信息经 SecretRedactor 脱敏，不入库/日志/前端",
        "详见 SECURITY.md / OPERATIONS.md"
      ].map((t) => `<div class="data-item"><span>${escAppHtml(t)}</span></div>`).join("");
    }
  }

  AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || [];
  AgentWorkbench.loadedModules.push("settings");
  AgentWorkbench.bindSettings = bindSettings;
  AgentWorkbench.loadSettings = loadSettings;
