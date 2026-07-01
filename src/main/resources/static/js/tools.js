"use strict";

var AgentWorkbench = window.AgentWorkbench = window.AgentWorkbench || {};


  function bindTools() {
    els.refreshTools?.addEventListener("click", () => void loadTools());
  }

  async function loadTools() {
    const [tools, servers] = await Promise.allSettled([requestJson(API.tools), requestJson(API.mcpServers)]);
    renderDataList(els.toolList, tools.status === "fulfilled" ? tools.value : [], (tool) => ({
      title: tool.name, meta: `${tool.provider || "local"} · 远程=${boolCn(Boolean(tool.remote))}`
    }));
    const serverItems = servers.status === "fulfilled" ? servers.value : [];
    if (serverItems.length === 0 && state.health && !state.health.mcpEnabled) {
      renderDataList(els.mcpServerList, [{ name: "MCP 未启用", hint: "设置 DEMO_MCP_ENABLED=true 并配置 spring.ai.mcp.client.* 以启用远程工具。" }],
        (server) => ({ title: server.name, meta: server.hint }));
      return;
    }
    renderDataList(els.mcpServerList, serverItems, (server) => ({
      title: server.name, meta: `${server.transport || "stdio"} · 启用=${boolCn(Boolean(server.enabled))}`
    }));
  }

  AgentWorkbench.loadedModules = AgentWorkbench.loadedModules || [];
  AgentWorkbench.loadedModules.push("tools");
  AgentWorkbench.bindTools = bindTools;
  AgentWorkbench.loadTools = loadTools;
