#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HEALTH_URL="${1:-http://localhost:8080/api/health}"

fail() {
  printf 'ERROR: %s\n' "$1" >&2
  if [[ -n "${HEALTH_BODY:-}" ]]; then
    printf 'Health response:\n%s\n' "$HEALTH_BODY" >&2
  fi
  exit 1
}

require_json_fragment() {
  local fragment="$1"
  local label="$2"
  if ! grep -Fq "$fragment" <<<"$HEALTH_BODY"; then
    fail "$label is not in the expected local Alibaba state"
  fi
}

cd "$ROOT_DIR"

if ! command -v curl >/dev/null 2>&1; then
  fail "curl is required to verify the local backend"
fi

HEALTH_BODY="$(curl -fsS "$HEALTH_URL")" || fail "Cannot reach $HEALTH_URL"

require_json_fragment '"success":true' "API wrapper"
require_json_fragment '"status":"UP"' "backend"
require_json_fragment '"modelConfigured":true' "DashScope chat"
require_json_fragment '"embeddingConfigured":true' "DashScope embedding"
require_json_fragment '"vectorStoreConfigured":true' "DashVector"
require_json_fragment '"ragRetriever":"DashVectorDocumentRetriever"' "RAG retriever"
require_json_fragment '"strictMode":true' "Alibaba strict mode"
require_json_fragment '"fallbackEnabled":false' "LLM fallback"
require_json_fragment '"keywordFallbackEnabled":false' "keyword fallback"
require_json_fragment '"workflowRuntime":"graph"' "workflow runtime"

model="$(sed -nE 's/.*"model":"([^"]+)".*/\1/p' <<<"$HEALTH_BODY")"
retriever="$(sed -nE 's/.*"ragRetriever":"([^"]+)".*/\1/p' <<<"$HEALTH_BODY")"
indexed_docs="$(sed -nE 's/.*"indexedDocumentCount":([0-9]+).*/\1/p' <<<"$HEALTH_BODY")"
mcp_enabled="$(sed -nE 's/.*"mcpEnabled":(true|false).*/\1/p' <<<"$HEALTH_BODY")"

printf 'Local Alibaba backend is ready.\n'
printf '  health: %s\n' "$HEALTH_URL"
printf '  model: %s\n' "${model:-unknown}"
printf '  retriever: %s\n' "${retriever:-unknown}"
printf '  indexedDocumentCount: %s\n' "${indexed_docs:-unknown}"
printf '  mcpEnabled: %s\n' "${mcp_enabled:-unknown}"
