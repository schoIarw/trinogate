#!/usr/bin/env bash
# Trino Gateway end-to-end smoke test.
#
# Spins up the mock Trino coordinator (scripts/mock-trino.py) and a real gateway
# fat jar, then exercises the full feature surface over HTTP:
#   auth 401 / metadata proxy / validation rejection / metadata allow /
#   forward + nextUri polling / file rules / admin dynamic rules / rate limit /
#   cancel / health check / metrics / admin flow stats.
#
# Usage: bash scripts/smoke-test.sh [path-to-trinogate.jar] [gw-port] [mock-port]
set -euo pipefail

JAR="${1:-trinogate-app/target/trinogate.jar}"
GW_PORT="${2:-19090}"
MOCK_PORT="${3:-19091}"
GW_BASE="http://127.0.0.1:${GW_PORT}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CONFIG="${SCRIPT_DIR}/ci-config.yaml"

PASS=0
FAIL=0

log() { echo "[smoke] $*"; }
pass() { PASS=$((PASS + 1)); log "PASS: $1"; }
fail() { FAIL=$((FAIL + 1)); log "FAIL: $1"; }

# assert_status <expected> <curl-args...>
assert_status() {
    local expected="$1"; shift
    local code
    code=$(curl -sS -o /tmp/smoke-body.json -w "%{http_code}" "$@" || true)
    if [ "$code" = "$expected" ]; then
        pass "HTTP $expected: $*"
    else
        fail "expected HTTP $expected, got $code: $* (body: $(head -c 300 /tmp/smoke-body.json 2>/dev/null || echo ''))"
    fi
}

# assert_contains <needle> <curl-args...>
assert_contains() {
    local needle="$1"; shift
    local body
    body=$(curl -sS "$@" || true)
    if printf '%s' "$body" | grep -qF "$needle"; then
        pass "body contains '$needle'"
    else
        fail "body missing '$needle' (body: $(printf '%s' "$body" | head -c 300))"
    fi
}

cleanup() {
    [ -n "${GW_PID:-}" ] && kill "$GW_PID" 2>/dev/null || true
    [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null || true
}
trap cleanup EXIT

log "=== Trino Gateway smoke test ==="

# --- start mock backend ---
MOCK_PORT="$MOCK_PORT" python3 "${SCRIPT_DIR}/mock-trino.py" >/tmp/mock-trino.log 2>&1 &
MOCK_PID=$!
sleep 1
log "mock trino started on :${MOCK_PORT} (pid ${MOCK_PID})"

# --- start gateway ---
java -jar "$JAR" "$CONFIG" >/tmp/gateway.log 2>&1 &
GW_PID=$!
log "gateway started (pid ${GW_PID}), waiting for readiness..."
READY=0
for _ in $(seq 1 30); do
    if curl -sS -o /dev/null "http://127.0.0.1:${GW_PORT}/v1/info" 2>/dev/null; then
        READY=1; break
    fi
    sleep 1
done
if [ "$READY" != "1" ]; then
    fail "gateway did not become ready"; tail -20 /tmp/gateway.log; exit 1
fi
pass "gateway ready on :${GW_PORT}"

H="X-Auth-User"

# 1. authentication: missing header -> 401
assert_status 401 -H "${H}: " -X POST -d "SELECT 1" "${GW_BASE}/v1/statement"

# 2. metadata proxy: /v1/info is forwarded to the healthy mock
assert_contains "trino-446-fake" -H "${H}: alice" "${GW_BASE}/v1/info"

# 3. validation: SELECT without WHERE/LIMIT is rejected (never reaches backend)
assert_status 200 -H "${H}: alice" -X POST -d "SELECT * FROM t" "${GW_BASE}/v1/statement"
assert_contains "WHERE 或 LIMIT" -H "${H}: alice" -X POST -d "SELECT * FROM t" "${GW_BASE}/v1/statement"

# 4. validation exemptions: SELECT without FROM and metadata statements are allowed
assert_status 200 -H "${H}: alice" -X POST -d "SELECT 1" "${GW_BASE}/v1/statement"
if curl -sS -H "${H}: alice" -X POST -d "SELECT 1" "${GW_BASE}/v1/statement" | grep -q '"error"'; then
    fail "SELECT 1 should be exempt"; else pass "SELECT 1 exempt"; fi
assert_status 200 -H "${H}: alice" -X POST -d "SHOW CATALOGS" "${GW_BASE}/v1/statement"
if curl -sS -H "${H}: alice" -X POST -d "SHOW CATALOGS" "${GW_BASE}/v1/statement" | grep -q '"error"'; then
    fail "SHOW CATALOGS should be allowed"; else pass "SHOW CATALOGS allowed"; fi

# 5. forward + single page
assert_status 200 -H "${H}: alice" -X POST -d "SELECT * FROM t WHERE x = 1" "${GW_BASE}/v1/statement"
assert_contains '"data":[[1]]' -H "${H}: alice" -X POST -d "SELECT * FROM t WHERE x = 1" "${GW_BASE}/v1/statement"

# 6. two-page polling with nextUri rewrite
NEXT=$(curl -sS -H "${H}: alice" -X POST -d "SELECT * FROM t WHERE x = 1 /* SLOW */" "${GW_BASE}/v1/statement" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("nextUri") or "")')
if [[ "$NEXT" == "${GW_BASE}/v1/statement/"* ]]; then
    pass "nextUri rewritten to gateway: $NEXT"
else
    fail "nextUri not rewritten: '$NEXT'"
fi
# polling is one-shot: the first GET consumes the query, so fetch once and check both code and body
POLL_FILE=/tmp/smoke-poll.json
POLL_CODE=$(curl -sS -o "$POLL_FILE" -w "%{http_code}" -H "${H}: alice" "${NEXT}")
if [ "$POLL_CODE" = "200" ] && grep -qF '"data":[[1]]' "$POLL_FILE"; then
    pass "poll page 2: HTTP 200 with data"
else
    fail "poll page 2: http=${POLL_CODE} body=$(head -c 200 "$POLL_FILE" 2>/dev/null || echo '')"
fi

# 7. file rule hot-loaded from scripts/ci-rules (block prod.orders)
assert_status 200 -H "${H}: alice" -X POST -d "SELECT * FROM prod.orders WHERE x = 1" "${GW_BASE}/v1/statement"
assert_contains "CI 文件规则" -H "${H}: alice" -X POST -d "SELECT * FROM prod.orders WHERE x = 1" "${GW_BASE}/v1/statement"

# 8. admin dynamic rules: add -> reject -> remove -> allow
RULE='{"id":"ci-block-pii","name":"ci block pii","action":"REJECT","version":1,"appliesTo":["QUERY"],"condition":{"forbiddenTables":["ods.pii.user_profile"]},"message":"动态规则：禁止访问 %s"}'
assert_status 201 -H "${H}: admin" -H "Content-Type: application/json" -X POST -d "$RULE" "${GW_BASE}/admin/rules"
assert_contains "动态规则" -H "${H}: alice" -X POST -d "SELECT * FROM ods.pii.user_profile WHERE x = 1" "${GW_BASE}/v1/statement"
assert_status 204 -H "${H}: admin" -X DELETE "${GW_BASE}/admin/rules?id=ci-block-pii"
if curl -sS -H "${H}: alice" -X POST -d "SELECT * FROM ods.pii.user_profile WHERE x = 1" "${GW_BASE}/v1/statement" | grep -q '"error"'; then
    fail "query should pass after rule removal"; else pass "query allowed after rule removal"; fi

# 9. rate limit: user 'limited' quota is 3/min -> 4th gets 429 + Retry-After
for i in 1 2 3; do
    assert_status 200 -H "${H}: limited" -X POST -d "SELECT * FROM t WHERE x = $i" "${GW_BASE}/v1/statement"
done
assert_status 429 -H "${H}: limited" -X POST -d "SELECT * FROM t WHERE x = 99" "${GW_BASE}/v1/statement"
RETRY=$(curl -sS -o /dev/null -D - -H "${H}: limited" -X POST -d "SELECT * FROM t WHERE x = 98" "${GW_BASE}/v1/statement" | grep -i '^Retry-After:' | tr -d '\r' || true)
if [ -n "$RETRY" ]; then pass "429 carries Retry-After: $RETRY"; else fail "429 missing Retry-After"; fi

# 10. cancel: DELETE nextUri -> 204, subsequent poll -> 404
NEXT2=$(curl -sS -H "${H}: alice" -X POST -d "SELECT * FROM t WHERE x = 2 /* SLOW */" "${GW_BASE}/v1/statement" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin).get("nextUri") or "")')
assert_status 204 -H "${H}: alice" -X DELETE "${NEXT2}"
assert_status 404 -H "${H}: alice" "${NEXT2}"

# 11. health check marks mock healthy
sleep 7
assert_contains '"healthy":true' -H "${H}: admin" "${GW_BASE}/admin/clusters"

# 12. metrics
assert_contains "gateway_queries_total" -H "${H}: alice" "${GW_BASE}/metrics"

# 13. admin flow stats
assert_contains "rateLimit" -H "${H}: admin" "${GW_BASE}/admin/flow?user=limited"

log "=== summary: PASS=${PASS} FAIL=${FAIL} ==="
[ "$FAIL" -eq 0 ]
