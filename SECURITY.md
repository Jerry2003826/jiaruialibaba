# 安全说明（单租户工业 MVP）

本服务面向**单租户、内测/小规模生产**。所有用户可见数据按 JWT subject 做 owner 隔离（非多租户 workspace 模型）。

## 生产启动硬门槛（ProductionStartupValidator）

`prod` profile 下应用启动时会校验以下条件，任一不满足即**拒绝启动**（一次性列出全部问题）：

- 不允许 H2 数据源，必须是 PostgreSQL（`spring.datasource.url` 以 `jdbc:postgresql:` 开头）。
- `demo.security.dev-token.enabled` 必须为 `false`。
- 不允许内置不安全 JWT secret；`issuer` 模式必须配置 `issuer-uri` 或 `jwk-set-uri`；`hmac` 模式（不推荐）必须使用 ≥32 字节的强随机 secret。
- `demo.alibaba.strict-mode=true`、`demo.ai.fallback-enabled=false`、`demo.rag.keyword-fallback-enabled=false`、`demo.rag.retriever=dashvector`。
- `demo.workflow.require-published-for-run=true`、`demo.workflow.allow-inline-run=false`、`demo.app.require-published-for-run=true`。
- DashScope（`spring.ai.dashscope.api-key`、embedding model）与 DashVector（endpoint、api-key）必须配置。

> 校验器可通过 `demo.production-validation.enabled=false` 关闭，**仅用于测试**；生产务必保持开启（默认开启）。

## 认证与授权

- 控制台 API（`/api/**`）使用 JWT resource server，基于 scope 授权。推荐生产用 `issuer` 模式对接真实 IdP（OIDC）。
- 公开端点：`/healthz`、`/actuator/health`（仅 UP/DOWN）。`/actuator/metrics|prometheus|info` 需 `SCOPE_health.read`。
- `dev-token`（`/api/auth/dev-token`）仅本地/演示；`@Profile("!prod")` 硬性排除，且需显式开启。

### Scope 一览

| 领域 | 读 | 写 | 运行 |
| --- | --- | --- | --- |
| App | `app.read` | `app.write` | `app.run` |
| Workflow | `workflow.read` | `workflow.edit` / `workflow.publish` | `workflow.run` |
| RAG | `rag.read` | `rag.write` | `rag.query` |
| Runs/Trace | `trace.read` | — | — |
| Audit | `audit.read` | — | — |
| Health | `health.read` | — | — |
| Tools | `tool.read` | — | — |

## Runtime API Key（面向业务系统调用）

- 通过 `POST /api/apps/{appId}/api-keys` 创建，**明文仅在创建时返回一次**，数据库只存 SHA-256 hash。
- 只能访问所属 app 的 runtime 端点：`/run`、`/chat`、`/chat/stream`（授予 `SCOPE_app.run`）。
- **不能**访问控制台管理 API；**不能**跨 app 调用（app A 的 key 调 app B 返回 403）。
- 请求方式：`Authorization: Bearer app_xxx` 或 `X-App-API-Key: app_xxx`。
- `DELETE /api/apps/{appId}/api-keys/{keyId}` 撤销后立即失效。

## 敏感信息脱敏

- 统一 `SecretRedactor` 识别并遮蔽 `authorization`、`api_key`、`secret`、`password`、`token`、`cookie` 等字段（token 计数字段如 `promptTokens` 例外）。
- Trace 的 input/output 落库前脱敏（含嵌套在 JSON 字符串中的密钥）；error response 不返回 stacktrace（`GlobalExceptionHandler` 返回通用错误码）。
- 审计日志只记录动作元数据（actor/action/resource/结果），**不记录 prompt 或密钥**。
- 每个请求分配 `X-Request-Id`（响应头 + 日志 MDC），便于关联，不含敏感信息。

## 网络与传输

- 生产建议前置 nginx/反向代理终止 TLS（见 `deploy/nginx/nginx.conf` 与 `docker-compose.prod.yml` 的 `proxy` profile），并透传 `X-Forwarded-For`、`X-Request-Id`。
- `demo.audit.trust-forwarded-headers` 默认是 `false`；只有在受信反向代理部署下才应开启。推荐用 `DEMO_AUDIT_TRUST_FORWARDED_HEADERS=true docker compose -f docker-compose.prod.yml --profile proxy up --build -d` 启动代理模式。
- 直连 app 的场景只建议用于可信内网调试；此时应保持 `demo.audit.trust-forwarded-headers=false`，避免客户端伪造源 IP。
- Actuator 的 metrics/prometheus 默认需要 scope；如需 Prometheus 抓取，建议独立管理端口或反向代理内网限制。

## 密钥与备份

- 严禁提交真实密钥：`.env`、`.env.prod` 已在 `.gitignore` / `.dockerignore` 中忽略；仓库仅保留 `*.example` 模板。
- 定期备份 PostgreSQL（见 `OPERATIONS.md`）。API key hash、审计日志、trace 均存于数据库，备份即可恢复。

## 报告漏洞

请通过私有渠道联系维护者，不要在公开 issue 中附带可利用细节或真实密钥。
