# PR9 Blockers And Highs 实现计划

> **面向 AI 代理的工作者：** 必需子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务实现此计划。步骤使用复选框（`- [ ]`）语法来跟踪进度。

**目标：** 以最小、安全、兼容的方式修复 PR #9 的 P0 blocker 与 P1 高优先级问题，使分支可安全合并。

**架构：** 保留当前 workflow replay/poll、keyword-first KB retrieval 和单租户模型，只在文案、校验、限流、安全默认值、敏感信息处理与热点写节流上做增量修复。所有改动沿用现有 Spring Boot DTO validation、service 校验、static assets 断言与集成测试模式。

**技术栈：** Java 21, Spring Boot, Bean Validation, Jackson, JPA, MockMvc, Maven Surefire, Docker Compose

---

### 任务 1：修正文档与前端中的 workflow 高亮语义

**文件：**
- 修改：`README.md`
- 修改：`OPERATIONS.md`
- 修改：`SECURITY.md`
- 修改：`src/main/resources/static/index.html`
- 修改：`src/main/resources/static/app.js`
- 修改：`src/main/resources/static/styles.css`
- 测试：`src/test/java/com/example/agentdemo/FrontendStaticAssetsTest.java`

- [ ] 编写失败测试与静态断言更新，要求不再出现误导性“实时高亮”文案
- [ ] 运行 `./mvnw -Dtest=FrontendStaticAssetsTest test` 验证失败
- [ ] 最小修改用户可见文案为“运行后节点状态回放 / trace-driven highlighting / 事件回放式高亮”
- [ ] 运行 `./mvnw -Dtest=FrontendStaticAssetsTest test` 验证通过
- [ ] 运行 grep 校验不再出现禁用短语

### 任务 2：收紧生产 compose 默认暴露面并增加 forwarded header 信任开关

**文件：**
- 修改：`docker-compose.prod.yml`
- 修改：`deploy/nginx/nginx.conf`
- 修改：`README.md`
- 修改：`OPERATIONS.md`
- 修改：`src/main/java/com/example/agentdemo/audit/AuditActorResolver.java`
- 修改：`src/main/java/com/example/agentdemo/audit/AuditProperties.java` 或相关配置类
- 修改：`src/main/resources/application.yml`
- 测试：`src/test/java/com/example/agentdemo/audit/AuditActorResolverTest.java`

- [ ] 先补 `AuditActorResolverTest` 失败用例：默认不信任 `X-Forwarded-For`，显式开启后才读取首个 IP，保留 IP 与 user-agent 截断断言
- [ ] 运行 `./mvnw -Dtest=*AuditActorResolver* test` 验证失败
- [ ] 修改 compose 默认仅 `expose: 8080`，`proxy` profile 暴露 nginx 端口，直连场景使用显式 `direct` profile
- [ ] 增加 `demo.audit.trust-forwarded-headers=false` 默认值，并在 proxy profile 环境变量中开启
- [ ] 运行 `./mvnw -Dtest=*AuditActorResolver* test`
- [ ] 运行 `docker compose -f docker-compose.prod.yml config -q`
- [ ] 运行 `docker compose -f docker-compose.prod.yml --profile proxy config -q`

### 任务 3：扩展 SecretRedactor 敏感字段识别

**文件：**
- 修改：`src/main/java/com/example/agentdemo/common/SecretRedactor.java`
- 测试：`src/test/java/com/example/agentdemo/common/SecretRedactorTest.java`

- [ ] 先补失败测试：`accessKeyId`、`privateKey`、`credentials`、`Authorization` 脱敏；`keyId`、`promptTokens`、`totalTokens` 不脱敏；嵌套 map / JsonNode 递归生效
- [ ] 运行 `./mvnw -Dtest=*SecretRedactor* test` 验证失败
- [ ] 最小扩展敏感命中词与非敏感 allowlist
- [ ] 运行 `./mvnw -Dtest=*SecretRedactor* test` 验证通过

### 任务 4：为 AppConfig 与 AppRunRequest 增加输入校验

**文件：**
- 修改：`src/main/java/com/example/agentdemo/app/AppConfig.java`
- 修改：`src/main/java/com/example/agentdemo/app/dto/CreateAppRequest.java`
- 修改：`src/main/java/com/example/agentdemo/app/dto/UpdateAppRequest.java`
- 修改：`src/main/java/com/example/agentdemo/app/dto/AppRunRequest.java`
- 修改：`src/main/java/com/example/agentdemo/app/AppController.java`
- 可能新增：`src/main/java/com/example/agentdemo/app/validation/...`
- 测试：`src/test/java/com/example/agentdemo/app/...`

- [ ] 先补失败测试：超长 `systemPrompt`、KB IDs 超限、`memoryMaxMessages` 超限、run input 过大返回 400、合法配置仍通过
- [ ] 运行 `./mvnw -Dtest=*App* test` 验证失败
- [ ] 增加 record 字段级 Bean Validation、嵌套 `@Valid` 与 run input size/field/depth validator，保持现有构造器兼容
- [ ] 运行 `./mvnw -Dtest=*App* test` 验证通过

### 任务 5：为 Knowledge ingestion 增加 MIME allowlist 与文件名清洗

**文件：**
- 修改：`src/main/java/com/example/agentdemo/knowledge/DocumentTextExtractor.java`
- 修改：`src/main/java/com/example/agentdemo/knowledge/KnowledgeBaseService.java`
- 修改：`src/main/java/com/example/agentdemo/knowledge/KnowledgeProperties.java` 或相关配置类
- 修改：`src/main/resources/application.yml`
- 测试：`src/test/java/com/example/agentdemo/knowledge/...`

- [ ] 先补失败测试：`application/zip` / `application/octet-stream` 被拒绝；空文件仍拒绝；危险文件名被清洗；txt/pdf/docx 继续通过
- [ ] 运行 `./mvnw -Dtest=*Knowledge* test` 验证失败
- [ ] 在调用 Tika parse 前做 MIME allowlist 拦截与安全文件名清洗，MIME not allowed 直接拒绝且不保存 FAILED document
- [ ] 运行 `./mvnw -Dtest=*Knowledge* test` 验证通过

### 任务 6：为 App API key last_used_at 更新加节流

**文件：**
- 修改：`src/main/java/com/example/agentdemo/app/apikey/AppApiKeyAuthenticationFilter.java`
- 修改：`src/main/resources/application.yml`
- 测试：`src/test/java/com/example/agentdemo/app/apikey/...`

- [ ] 先补失败测试：首次调用更新、60 秒内不更新、超过阈值后更新
- [ ] 运行 `./mvnw -Dtest=*App* test` 验证失败
- [ ] 增加可配置更新时间窗，保持 best-effort 语义
- [ ] 运行 `./mvnw -Dtest=*App* test` 验证通过

### 任务 7：校正文档中的 KB retrieval 当前能力表述

**文件：**
- 修改：`README.md`
- 修改：相关 API / 功能矩阵文档

- [ ] 更新文档表述为 `KB 隔离 keyword retrieval + reranker extension point`，说明 DashVector indexing/outbox 已有而 KB-aware vector retrieval 待增强
- [ ] grep 确认无误导性“已完整支持 KB 向量检索”表述

### 任务 8：最终验证

**文件：**
- 无新增代码文件

- [ ] 运行 `./mvnw -Dtest='*RateLimit*' test`
- [ ] 运行 `./mvnw -Dtest='*SecretRedactor*' test`
- [ ] 运行 `./mvnw -Dtest='*AuditActorResolver*' test`
- [ ] 运行 `./mvnw -Dtest='*Knowledge*' test`
- [ ] 运行 `./mvnw -Dtest='*App*' test`
- [ ] 运行 `./mvnw -Dtest=FrontendStaticAssetsTest test`
- [ ] 运行 `./mvnw clean test`
- [ ] 运行 `docker compose -f docker-compose.prod.yml config -q`
- [ ] 运行 `docker compose -f docker-compose.prod.yml --profile proxy config -q`
- [ ] 如果 Docker daemon 可用，运行 `docker build -t agent-backend-demo:review .`
