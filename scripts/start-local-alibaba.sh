#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

PORT="${PORT:-8080}"
HEALTH_URL="http://localhost:${PORT}/api/health"
# Public, unauthenticated liveness probe used as the readiness gate before the authenticated
# strict-mode verification runs.
HEALTHZ_URL="http://localhost:${PORT}/healthz"
LOG_DIR="$ROOT_DIR/var/log"
LOG_FILE="$LOG_DIR/local-alibaba.log"
PID_FILE="$ROOT_DIR/var/local-alibaba.pid"
SERVICE_LABEL="local.agent-backend-demo.alibaba"
BACKGROUND=false
RESTART=false
NO_DB=false
NO_BUILD=false
STOP=false

usage() {
  cat <<'USAGE'
Usage:
  ./scripts/start-local-alibaba.sh [--restart] [--background] [--no-db] [--no-build]
  ./scripts/start-local-alibaba.sh --stop

Starts the local full Alibaba stack with:
  Spring profile: dev
  Profile group: dev,alibaba-strict,postgres,workflow-graph
  Config file: .env

Default mode runs in the current terminal, like a normal dev server. Keep that
terminal open while using http://localhost:8080/.

Options:
  --restart     Stop the process currently listening on PORT before starting.
  --background  Start with nohup and wait for /api/health. Works in a normal terminal.
  --no-db       Do not try to start PostgreSQL with docker compose if the port is closed.
  --no-build    Reuse the existing target jar instead of running Maven package first.
  --stop        Stop the tracked background process, launch agent, or process listening on PORT.
USAGE
}

log() {
  printf '%s\n' "$*"
}

die() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

persist_env_value() {
  local name="$1"
  local value="$2"
  local tmp_file
  tmp_file="$(mktemp)"
  if grep -qE "^${name}=" .env; then
    awk -v key="$name" -v val="$value" 'BEGIN { FS = OFS = "=" } $1 == key { print key "=" val; next } { print }' .env >"$tmp_file"
    mv "$tmp_file" .env
  else
    rm -f "$tmp_file"
    printf '\n%s=%s\n' "$name" "$value" >> .env
  fi
  chmod 600 .env
}

listening_pids() {
  lsof -tiTCP:"$PORT" -sTCP:LISTEN 2>/dev/null || true
}

stop_backend() {
  if [[ "$(uname -s)" == "Darwin" ]] && command -v launchctl >/dev/null 2>&1; then
    launchctl bootout "gui/$(id -u)" "$HOME/Library/LaunchAgents/${SERVICE_LABEL}.plist" >/dev/null 2>&1 || true
    launchctl bootout "gui/$(id -u)" "$ROOT_DIR/var/${SERVICE_LABEL}.plist" >/dev/null 2>&1 || true
    launchctl remove "$SERVICE_LABEL" >/dev/null 2>&1 || true
  fi

  local pids=""
  if [[ -f "$PID_FILE" ]]; then
    local tracked_pid
    tracked_pid="$(cat "$PID_FILE" 2>/dev/null || true)"
    if [[ -n "$tracked_pid" ]] && kill -0 "$tracked_pid" 2>/dev/null; then
      pids="$tracked_pid"
    fi
  fi

  local port_pids
  port_pids="$(listening_pids)"
  if [[ -n "$port_pids" ]]; then
    pids="${pids}"$'\n'"${port_pids}"
  fi

  pids="$(printf '%s\n' "$pids" | awk 'NF && !seen[$0]++')"
  if [[ -z "$pids" ]]; then
    rm -f "$PID_FILE"
    log "No local backend is listening on port $PORT."
    return 0
  fi

  log "Stopping local backend process(es): $(printf '%s' "$pids" | tr '\n' ' ')"
  while IFS= read -r pid; do
    [[ -n "$pid" ]] && kill "$pid" 2>/dev/null || true
  done <<<"$pids"

  for _ in {1..30}; do
    if [[ -z "$(listening_pids)" ]]; then
      rm -f "$PID_FILE"
      log "Stopped."
      return 0
    fi
    sleep 1
  done

  die "Port $PORT is still busy after waiting for shutdown"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --restart)
      RESTART=true
      ;;
    --background)
      BACKGROUND=true
      ;;
    --no-db)
      NO_DB=true
      ;;
    --no-build)
      NO_BUILD=true
      ;;
    --stop)
      STOP=true
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      die "Unknown option: $1"
      ;;
  esac
  shift
done

if [[ "$STOP" == true ]]; then
  stop_backend
  exit 0
fi

[[ -f .env ]] || die ".env not found. Run 'cp .env.example .env' and fill the local Alibaba credentials first."

set -a
# shellcheck disable=SC1091
source .env
set +a

export DEMO_ALIBABA_STRICT_MODE="${DEMO_ALIBABA_STRICT_MODE:-true}"
export DEMO_AI_FALLBACK_ENABLED="${DEMO_AI_FALLBACK_ENABLED:-false}"
export DEMO_RAG_KEYWORD_FALLBACK_ENABLED="${DEMO_RAG_KEYWORD_FALLBACK_ENABLED:-false}"
export DEMO_RAG_RETRIEVER="${DEMO_RAG_RETRIEVER:-dashvector}"
export DEMO_WORKFLOW_RUNTIME="${DEMO_WORKFLOW_RUNTIME:-graph}"
export DEMO_SECURITY_JWT_MODE="${DEMO_SECURITY_JWT_MODE:-hmac}"
export DEMO_SECURITY_DEV_TOKEN_ENABLED="${DEMO_SECURITY_DEV_TOKEN_ENABLED:-true}"

if [[ -z "${DEMO_SECURITY_JWT_SECRET:-}" ]]; then
  if command -v openssl >/dev/null 2>&1; then
    DEMO_SECURITY_JWT_SECRET="$(openssl rand -base64 48)"
  elif command -v uuidgen >/dev/null 2>&1; then
    DEMO_SECURITY_JWT_SECRET="$(uuidgen)$(uuidgen)"
  else
    DEMO_SECURITY_JWT_SECRET="local-dev-jwt-secret-$(date +%s)-change-me"
  fi
  export DEMO_SECURITY_JWT_SECRET
  persist_env_value "DEMO_SECURITY_JWT_SECRET" "$DEMO_SECURITY_JWT_SECRET"
  log "Generated and persisted a local JWT secret in .env."
fi

missing=()
require_var() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
}

if [[ -z "${AI_DASHSCOPE_API_KEY:-}" && -z "${DASHSCOPE_API_KEY:-}" ]]; then
  missing+=("AI_DASHSCOPE_API_KEY or DASHSCOPE_API_KEY")
fi

for name in \
  DB_HOST DB_PORT DB_NAME DB_USERNAME DB_PASSWORD \
  AI_DASHSCOPE_CHAT_MODEL \
  AI_DASHSCOPE_EMBEDDING_MODEL AI_DASHSCOPE_EMBEDDING_DIMENSION \
  DASHVECTOR_ENDPOINT DASHVECTOR_API_KEY DASHVECTOR_COLLECTION DASHVECTOR_DIMENSION DASHVECTOR_METRIC; do
  require_var "$name"
done

if [[ "${#missing[@]}" -gt 0 ]]; then
  printf 'Missing required local Alibaba config in .env:\n' >&2
  printf '  - %s\n' "${missing[@]}" >&2
  exit 1
fi

if [[ "$DEMO_ALIBABA_STRICT_MODE" != "true" || "$DEMO_AI_FALLBACK_ENABLED" != "false" || "$DEMO_RAG_KEYWORD_FALLBACK_ENABLED" != "false" || "$DEMO_RAG_RETRIEVER" != "dashvector" || "$DEMO_WORKFLOW_RUNTIME" != "graph" ]]; then
  die "Local Alibaba launch requires strict=true, fallback=false, keywordFallback=false, retriever=dashvector, workflow=graph"
fi

if command -v nc >/dev/null 2>&1; then
  if ! nc -z "${DB_HOST}" "${DB_PORT}" >/dev/null 2>&1; then
    if [[ "$NO_DB" == false ]] && command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1; then
      log "PostgreSQL is not listening at ${DB_HOST}:${DB_PORT}; starting docker compose postgres."
      docker compose up -d postgres
      for _ in {1..30}; do
        nc -z "${DB_HOST}" "${DB_PORT}" >/dev/null 2>&1 && break
        sleep 1
      done
    fi
  fi
  nc -z "${DB_HOST}" "${DB_PORT}" >/dev/null 2>&1 || die "PostgreSQL is not reachable at ${DB_HOST}:${DB_PORT}"
else
  log "nc is not installed; skipping PostgreSQL port preflight."
fi

if [[ -n "$(listening_pids)" ]]; then
  if "$ROOT_DIR/scripts/verify-local-alibaba.sh" "$HEALTH_URL" >/dev/null 2>&1; then
    if [[ "$RESTART" == true ]]; then
      stop_backend
    else
      log "Local Alibaba backend is already running at http://localhost:${PORT}/"
      "$ROOT_DIR/scripts/verify-local-alibaba.sh" "$HEALTH_URL"
      exit 0
    fi
  elif [[ "$RESTART" == true ]]; then
    stop_backend
  else
    die "Port $PORT is busy but is not a verified local Alibaba backend. Re-run with --restart to stop it first."
  fi
fi

mkdir -p "$LOG_DIR"
: > "$LOG_FILE"

if [[ "$NO_BUILD" == false ]]; then
  log "Packaging application jar with Maven (tests skipped)..."
  ./mvnw -DskipTests package >>"$LOG_FILE" 2>&1
fi

jar_path="$(find "$ROOT_DIR/target" -maxdepth 1 -name 'agent-backend-demo-*.jar' ! -name '*.original' | sort | tail -n 1)"
[[ -n "$jar_path" && -f "$jar_path" ]] || die "No runnable jar found under target/. Run without --no-build first."

if [[ "$BACKGROUND" == false ]]; then
  log "Starting local Alibaba backend in the foreground from $jar_path..."
  log "Open http://localhost:${PORT}/ after the log says Tomcat started."
  exec java -Dspring.profiles.active=dev -Dserver.port="$PORT" -jar "$jar_path"
fi

log "Starting local Alibaba backend in the background..."
log "Log: $LOG_FILE"
nohup java -Dspring.profiles.active=dev -Dserver.port="$PORT" -jar "$jar_path" >>"$LOG_FILE" 2>&1 </dev/null &
backend_pid=$!
printf '%s\n' "$backend_pid" > "$PID_FILE"

for _ in {1..90}; do
  # Gate on the public /healthz probe first, then assert the full strict Alibaba state.
  if curl -fsS "$HEALTHZ_URL" >/dev/null 2>&1 \
      && "$ROOT_DIR/scripts/verify-local-alibaba.sh" "$HEALTH_URL" >/dev/null 2>&1; then
    "$ROOT_DIR/scripts/verify-local-alibaba.sh" "$HEALTH_URL"
    log "Open http://localhost:${PORT}/"
    log "PID: $backend_pid"
    exit 0
  fi

  if ! kill -0 "$backend_pid" 2>/dev/null; then
    tail -n 80 "$LOG_FILE" >&2 || true
    die "Backend process exited before becoming healthy"
  fi
  sleep 1
done

tail -n 80 "$LOG_FILE" >&2 || true
die "Backend did not become healthy within 90 seconds"
