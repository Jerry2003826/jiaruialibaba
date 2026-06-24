# Dify-like Workbench Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the missing Dify-like frontend and workflow canvas to the existing Spring AI Alibaba backend demo.

**Architecture:** Keep the backend API intact and add a Spring Boot static frontend under `src/main/resources/static`. The frontend is a no-build vanilla HTML/CSS/JS workbench that calls the existing workflow, RAG, chat, tool, and trace APIs. Workflow canvas layout is client-side UI state; executable workflow definitions sent to the backend remain pure `nodes` + `edges`.

**Tech Stack:** Java 21, Spring Boot MVC static resources, JUnit/MockMvc, vanilla HTML/CSS/JavaScript, SVG edge layer for the workflow canvas.

---

## Scope

This is not a complete Dify clone. This plan only builds:

- A first-screen AI Agent workbench served at `/`.
- A workflow canvas with palette nodes, draggable nodes, edge creation, config inspector, validation, save, run, run graph, and trace step viewing.
- Small Chat, RAG, Tools, and Runs panels that reuse existing backend APIs.
- README startup notes for the new UI.

This plan does not build tenants, auth, app marketplace, production datasets, deployment, or a React/Vue frontend stack.

## File Structure

- Create: `src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java`
  - MockMvc tests proving `/`, `/app.js`, and `/styles.css` are served and contain the expected workbench hooks.
- Create: `src/main/resources/static/index.html`
  - App shell, navigation, workflow canvas, inspector, execution panel, chat/RAG/tool/runs panels.
- Create: `src/main/resources/static/styles.css`
  - Operational SaaS-style layout, canvas nodes, toolbars, panels, responsive behavior.
- Create: `src/main/resources/static/app.js`
  - Browser controller for API calls, workflow state, SVG edges, node dragging, config editing, save/run/trace handling.
- Modify: `README.md`
  - Add frontend URL and workflow canvas usage notes.

## Task 1: Static Workbench Contract Test

- [x] **Step 1: Write failing static asset test**

Create `src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java`:

```java
package com.example.agentdemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendDemoApplication.class, properties = {
        "spring.ai.dashscope.api-key=",
        "demo.dashvector.endpoint=",
        "demo.dashvector.api-key=",
        "demo.rag.retriever=keyword"
})
@AutoConfigureMockMvc
class FrontendStaticAssetsTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void servesWorkbenchHomePage() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("AI Agent Workbench")))
                .andExpect(content().string(containsString("workflow-canvas")))
                .andExpect(content().string(containsString("/app.js")))
                .andExpect(content().string(containsString("/styles.css")));
    }

    @Test
    void servesWorkbenchJavaScript() throws Exception {
        mockMvc.perform(get("/app.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("WorkflowCanvasController")))
                .andExpect(content().string(containsString("/api/workflows/run")))
                .andExpect(content().string(containsString("/api/workflows/validate")))
                .andExpect(content().string(containsString("/api/rag/chat")));
    }

    @Test
    void servesWorkbenchStylesheet() throws Exception {
        mockMvc.perform(get("/styles.css"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(".workflow-canvas")))
                .andExpect(content().string(containsString(".canvas-node")))
                .andExpect(content().string(containsString(".inspector-panel")));
    }

}
```

- [x] **Step 2: Run test to verify it fails**

Run:

```bash
./mvnw -Dtest=com.example.agentdemo.FrontendStaticAssetsTest test
```

Expected: FAIL because `/` is not yet backed by `index.html` and `/app.js` / `/styles.css` do not exist.

## Task 2: Add Static Workbench Files

- [x] **Step 1: Create `index.html`**

Create a static app shell with these stable IDs/classes because tests and JavaScript depend on them:

- `#app`
- `#workflow-canvas`
- `#edge-layer`
- `#node-layer`
- `#node-palette`
- `#node-inspector`
- `#run-output`
- `#trace-steps`
- navigation buttons with `data-view="workflow"`, `chat`, `rag`, `tools`, `runs`

- [x] **Step 2: Create `styles.css`**

Implement a restrained operational UI:

- Fixed app shell with left navigation, top toolbar, main canvas, right inspector.
- Canvas nodes use stable dimensions so dragging and status changes do not resize layout.
- Buttons and controls have clear focus/disabled/hover states.
- Mobile layout stacks the canvas, inspector, and execution panel.

- [x] **Step 3: Create `app.js`**

Implement `window.WorkflowCanvasController` with these responsibilities:

- Load node schemas from `GET /api/workflows/node-schemas`.
- Maintain workflow state as `{ nodes, edges }` and canvas positions separately.
- Add nodes from the palette with default config from schema fields.
- Drag nodes on the canvas using pointer events.
- Connect nodes by selecting a source node output and a target node.
- Edit node config from the inspector.
- Build executable workflow definitions without UI-only fields.
- Call:
  - `POST /api/workflows/validate`
  - `POST /api/workflows/preview-graph`
  - `POST /api/workflows/run`
  - `POST /api/workflows/definitions`
  - `PUT /api/workflows/definitions/{definitionId}`
  - `GET /api/workflows/definitions`
  - `GET /api/workflows/runs/{runId}/graph`
  - `GET /api/runs/{runId}/steps`
  - `POST /api/chat`
  - `POST /api/rag/documents`
  - `POST /api/rag/chat`
  - `GET /api/tools`
  - `GET /api/tools/mcp/servers`
  - `GET /api/runs`
- Render API results and errors without throwing uncaught promise errors.

- [x] **Step 4: Run static asset test**

Run:

```bash
./mvnw -Dtest=com.example.agentdemo.FrontendStaticAssetsTest test
```

Expected: PASS.

## Task 3: README and Verification

- [x] **Step 1: Update README**

Add a "Frontend Workbench" section with:

- URL: `http://localhost:8080/`
- No Node.js build step.
- Workflow canvas features.
- Limitation: canvas positions are local browser UI state; executable definitions remain backend `nodes` + `edges`.

- [x] **Step 2: Run focused frontend/static tests**

Run:

```bash
./mvnw -Dtest=com.example.agentdemo.FrontendStaticAssetsTest test
```

Expected: PASS.

- [x] **Step 3: Run full backend package**

Run:

```bash
./mvnw clean package
```

Expected: BUILD SUCCESS.

- [x] **Step 4: Start the app and verify UI route**

Run:

```bash
./mvnw spring-boot:run
curl -s http://localhost:8080/ | head
curl -s http://localhost:8080/app.js | grep WorkflowCanvasController
```

Expected: HTML includes `AI Agent Workbench`, JavaScript includes `WorkflowCanvasController`.

- [x] **Step 5: Commit and push**

Run:

```bash
git add README.md src/main/resources/static src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java docs/superpowers/plans/2026-06-24-dify-like-workbench.md
git commit -m "Add Dify-like workflow workbench"
git -c http.proxy=http://127.0.0.1:7897 -c https.proxy=http://127.0.0.1:7897 push origin main
```
