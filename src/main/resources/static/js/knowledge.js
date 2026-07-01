"use strict";

window.AgentWorkbench = window.AgentWorkbench || {};


  // ============================================================
  // 知识库（Knowledge Base 产品模型）
  // ============================================================
  const kbState = { selectedKbId: null };

  function bindKnowledge() {
    els.refreshKb?.addEventListener("click", () => void loadKnowledgeBases());
    els.createKb?.addEventListener("click", () => void createKnowledgeBase());
    els.uploadKbText?.addEventListener("click", () => void uploadKbText());
    els.uploadKbFile?.addEventListener("click", () => void uploadKbFile());
    els.refreshKbDocs?.addEventListener("click", () => { if (kbState.selectedKbId) void loadKbDocs(kbState.selectedKbId); });
    els.kbSearch?.addEventListener("click", () => void searchKb());
    els.kbList?.addEventListener("click", (event) => {
      const card = event.target.closest("[data-kb-id]");
      if (card) void selectKb(card.dataset.kbId);
    });
  }

  async function loadKnowledgeBases() {
    if (!els.kbList) return;
    try {
      const list = await requestJson(API.knowledgeBases);
      els.kbList.innerHTML = (list && list.length) ? "" : '<div class="empty-state">还没有知识库。</div>';
      (list || []).forEach((kb) => {
        const item = document.createElement("div");
        item.className = "data-item";
        item.dataset.kbId = kb.kbId;
        item.innerHTML = `<div class="data-item-main"><strong>${escAppHtml(kb.name)}</strong>
          <span class="muted">${kb.documentCount} 篇</span></div>
          <div class="data-item-actions"><button class="btn btn-ghost btn-sm" type="button">打开</button></div>`;
        els.kbList.appendChild(item);
      });
    } catch (error) { toast(error.message, true); }
  }

  async function createKnowledgeBase() {
    const name = (els.createKbName?.value || "").trim();
    if (!name) { toast("请输入知识库名称", true); return; }
    try {
      const kb = await requestJson(API.knowledgeBases, { method: "POST", body: { name } });
      toast(`已创建知识库「${kb.name}」`);
      if (els.createKbName) els.createKbName.value = "";
      await loadKnowledgeBases();
      await selectKb(kb.kbId);
    } catch (error) { toast(error.message, true); }
  }

  async function selectKb(kbId) {
    kbState.selectedKbId = kbId;
    if (els.kbDetail) els.kbDetail.hidden = false;
    if (els.kbDetailTitle) els.kbDetailTitle.textContent = `知识库 ${kbId}`;
    if (els.kbSearchResults) els.kbSearchResults.innerHTML = "";
    await loadKbDocs(kbId);
  }

  async function loadKbDocs(kbId) {
    if (!els.kbDocList) return;
    try {
      const page = await requestJson(`${API.kbDocs(kbId)}?page=0&size=50`);
      const docs = page?.content || [];
      els.kbDocList.innerHTML = docs.length ? "" : '<div class="empty-state">还没有文档。</div>';
      docs.forEach((doc) => {
        const item = document.createElement("div");
        item.className = "data-item";
        const statusClass = doc.indexStatus === "READY" ? "badge-ok" : (doc.indexStatus === "FAILED" ? "badge-danger" : "badge-draft");
        item.innerHTML = `<div class="data-item-main"><strong>${escAppHtml(doc.title)}</strong>
          <span class="badge ${statusClass}">${escAppHtml(doc.indexStatus)}</span>
          <span class="muted">${escAppHtml(doc.sourceType || "")} ${doc.contentLength || 0} 字${doc.errorMessage ? " · " + escAppHtml(doc.errorMessage) : ""}</span></div>`;
        els.kbDocList.appendChild(item);
      });
    } catch (error) { toast(error.message, true); }
  }

  async function uploadKbText() {
    const kbId = kbState.selectedKbId;
    if (!kbId) { toast("请先选择知识库", true); return; }
    const content = (els.kbDocContent?.value || "").trim();
    if (!content) { toast("请输入文本内容", true); return; }
    try {
      await requestJson(API.kbTextDoc(kbId), { method: "POST",
        body: { title: (els.kbDocTitle?.value || "").trim() || null, content } });
      toast("已保存文本文档");
      await loadKbDocs(kbId);
    } catch (error) { toast(error.message, true); }
  }

  async function uploadKbFile() {
    const kbId = kbState.selectedKbId;
    if (!kbId) { toast("请先选择知识库", true); return; }
    const file = els.kbFile?.files?.[0];
    if (!file) { toast("请选择文件", true); return; }
    try {
      const form = new FormData();
      form.append("file", file);
      const response = await fetch(API.kbFileDoc(kbId), { method: "POST", headers: authHeaders({}), body: form });
      const text = await response.text();
      const payload = text ? JSON.parse(text) : null;
      if (!response.ok || payload?.success === false) throw new Error(payload?.message || `HTTP ${response.status}`);
      toast("已上传并解析文件");
      if (els.kbFile) els.kbFile.value = "";
      await loadKbDocs(kbId);
    } catch (error) { toast(error.message, true); }
  }

  async function searchKb() {
    const kbId = kbState.selectedKbId;
    if (!kbId) { toast("请先选择知识库", true); return; }
    const query = (els.kbSearchQuery?.value || "").trim();
    if (!query) { toast("请输入查询", true); return; }
    try {
      const result = await requestJson(API.kbSearch(kbId), { method: "POST", body: { query } });
      const citations = result?.citations || [];
      els.kbSearchResults.innerHTML = citations.length ? "" : '<div class="empty-state">无匹配结果。</div>';
      citations.forEach((c) => {
        const item = document.createElement("div");
        item.className = "data-item";
        item.innerHTML = `<div class="data-item-main"><strong>${escAppHtml(c.title)}</strong>
          <span class="muted">score ${Number(c.score).toFixed(2)} · #${c.chunkIndex}</span>
          <div class="muted">${escAppHtml(c.snippet)}</div></div>`;
        els.kbSearchResults.appendChild(item);
      });
    } catch (error) { toast(error.message, true); }
  }
