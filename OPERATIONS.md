# 运维手册（Operations）

面向单租户内测/小规模生产部署。

## 1. 生产部署（Docker Compose）

```bash
cp .env.prod.example .env.prod   # 填入真实 DB / JWT issuer / DashScope / DashVector 配置
DEMO_AUDIT_TRUST_FORWARDED_HEADERS=true docker compose -f docker-compose.prod.yml --profile proxy up --build -d
# 可选：带缓存
docker compose -f docker-compose.prod.yml --profile cache up -d  # 额外启动 redis（占位，暂未接入）
```

- app 以 `SPRING_PROFILES_ACTIVE=prod,postgres` 运行，非 root 用户，内置 `/healthz` 健康检查。
- 默认生产模式下 app 不向宿主机发布端口，只在 compose 网络内 `expose: 8080`，由 nginx 反向代理访问。
- `DEMO_AUDIT_TRUST_FORWARDED_HEADERS` 默认保持 `false`；仅在受信反向代理部署时显式设为 `true`，否则审计 IP 一律取 `request.getRemoteAddr()`。
- 启动即执行生产硬门槛校验（见 `SECURITY.md`）；配置不合规会拒绝启动并打印全部问题。
- 推荐探活：`curl -fsS http://localhost/healthz` → `{"status":"UP"}`。

### 内网直连 / 调试

```bash
docker compose -f docker-compose.prod.yml -f docker-compose.direct.yml up --build -d
curl -fsS http://localhost:8080/healthz
```

- `docker-compose.direct.yml` 仅用于可信内网直连或临时调试，会显式发布 app 端口并关闭 forwarded header 信任。

### 查看状态 / 日志

```bash
docker compose -f docker-compose.prod.yml ps
docker compose -f docker-compose.prod.yml logs -f app
```

## 2. 数据库迁移（Flyway）

- `postgres` profile 下 Flyway 自动执行 `src/main/resources/db/migration/V*.sql`，随后 Hibernate `validate` 校验 schema。
- 已有（Flyway 之前的）库通过 `baseline-on-migrate=true` 打基线到 V1，再应用 V2+。
- 新增结构一律走 Flyway migration（禁止在生产用 `ddl-auto=update`）。
- 查看已应用版本：

```sql
select version, description, success from flyway_schema_history order by installed_rank;
```

## 3. PostgreSQL 备份与恢复

```bash
# 备份
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U "$DB_USERNAME" "$DB_NAME" > backup_$(date +%F).sql

# 恢复（目标库需已存在且为空）
cat backup_2026-07-01.sql | docker compose -f docker-compose.prod.yml exec -T postgres \
  psql -U "$DB_USERNAME" -d "$DB_NAME"
```

数据卷 `agent_demo_pg_data` 持久化数据；升级镜像不影响数据。备份即覆盖 app 元数据、审计日志、trace、token usage、API key hash 等。

## 4. DashVector 重建索引（reindex）

RAG 向量写入通过 outbox（`vector_outbox_events`）异步落到 DashVector，保证 exactly-once。

- 单文档重建：`POST /api/knowledge-bases/{kbId}/documents/{documentId}/reindex`（P1 起提供）或对现有 `rag` 文档重新保存。
- 排查卡住的 outbox：

```sql
select status, count(*) from vector_outbox_events group by status;
```

- `FAILED`/`DEAD_LETTER` 事件可在修复 DashVector 连接后由 worker 重试（worker 崩溃可恢复、带租约）。

## 5. 健康与指标

| 端点 | 用途 |
| --- | --- |
| `GET /healthz` | 公开存活探针（UP/DOWN） |
| `GET /actuator/health` | 标准存活/就绪探针（UP/DOWN） |
| `GET /actuator/metrics` | 指标（需 `SCOPE_health.read`） |
| `GET /actuator/prometheus` | Prometheus 抓取（需 `SCOPE_health.read`） |
| `GET /api/health` | 受保护诊断（模型/向量库/runtime 状态，需 `SCOPE_health.read`） |
| `GET /api/runs/{runId}/usage` | 单次 run 的 token 用量汇总（需 `SCOPE_trace.read`） |
| `GET /api/audit-logs` | 审计日志（需 `SCOPE_audit.read`） |

Prometheus 抓取示例（携带 bearer token）：

```yaml
scrape_configs:
  - job_name: agent-backend
    metrics_path: /actuator/prometheus
    authorization:
      type: Bearer
      credentials: "<token-with-health.read-scope>"
    static_configs:
      - targets: ["agent-backend:8080"]
```

## 6. 日志关联

每个请求分配 `X-Request-Id`（可由入站头透传），写入响应头与日志 MDC（日志格式 `[app,<requestId>]`），审计行也记录该 id，便于跨日志/trace/审计关联。
