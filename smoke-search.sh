#!/usr/bin/env bash
#
# Smoke-test the search pipeline against a running stack.
#
# Fires the query corpus from README.md's "Verified behaviour" table at the
# search API and asserts the routing decision for each one. With --loki it also
# confirms every decision reached Loki, which exercises the whole observability
# path (backend DEBUG -> Alloy -> Loki -> queryable) in the same run.
#
#   ./smoke-search.sh                          # API assertions only
#   ./smoke-search.sh --loki                   # ...plus log-pipeline check
#   API=http://localhost:8080 ./smoke-search.sh  # against a local mvn spring-boot:run
#
set -euo pipefail

API="${API:-http://192.168.139.2}"   # istio-ingressgateway external IP
USER_ID="${USER_ID:-user_1}"         # recipient resolution needs a user
LOKI_PORT="${LOKI_PORT:-3100}"
CHECK_LOKI=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --loki) CHECK_LOKI=true; shift ;;
        -h|--help) sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
        *) echo "unknown argument: $1" >&2; exit 2 ;;
    esac
done

# query | expected feature | expected action
#
# Confidence is deliberately not asserted: it moves with any retrieval weight
# change in application.yml (search.weights.*), so pinning it would fail on
# legitimate tuning. It is printed for information only.
CASES=(
    "trf|transfer|navigate"
    "tran|transfer|navigate"
    "e-stmt|download_e_statement|navigate"
    "check balance|check_balance|navigate"
    "block my card|block_card|navigate"
    "where did my money go|spending_insights|navigate"
    "transfer to mom's 10000 usd|transfer|prefill_form"
    "send 250 usd to landlord|transfer|prefill_form"
    "download e-statement for march|download_e_statement|prefill_form"
    "show me last month transactions over 500|transaction_history|prefill_form"
)

# Window for the Loki lookup. Backdated a few seconds so clock skew between
# this host and the cluster cannot hide the first request.
START_EPOCH=$(( $(date +%s) - 5 ))

printf '%s\n' "Search smoke test  ->  $API"
printf '\n'
printf '%-42s %-22s %-6s %-14s %-7s %s\n' QUERY FEATURE CONF ACTION PARAMS RESULT
printf '%-42s %-22s %-6s %-14s %-7s %s\n' \
    "------------------------------------------" "----------------------" \
    "------" "--------------" "-------" "------"

failures=0
LOGGED_QUERIES=()

for case in "${CASES[@]}"; do
    IFS='|' read -r query want_feature want_action <<<"$case"

    # Only the extraction path logs a decision. SearchOrchestrator's log.debug
    # sits after Stage 3, so every navigate() early return — typeahead, a closed
    # signal gate, low confidence — returns before reaching it. Expecting a log
    # line for those would report a pipeline failure that is really just the
    # code path not logging.
    if [[ "$want_action" == "prefill_form" ]]; then
        LOGGED_QUERIES+=("$query")
    fi

    # Built with json.dumps rather than string interpolation — one case
    # contains an apostrophe, and hand-quoting that is how this breaks.
    body=$(python3 -c \
        'import json,sys; print(json.dumps({"query": sys.argv[1], "user_id": sys.argv[2]}))' \
        "$query" "$USER_ID")

    resp=$(curl -sS -X POST "$API/api/search" \
        -H 'content-type: application/json' \
        --data-raw "$body" -m 20 || true)

    parsed=$(printf '%s' "$resp" | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    print("|||")
    sys.exit(0)
print("|".join(str(d.get(k)) for k in
                ("matched_feature", "confidence", "action", "params_extracted")))
')
    IFS='|' read -r feature conf action params <<<"$parsed"

    if [[ "$feature" == "$want_feature" && "$action" == "$want_action" ]]; then
        result="PASS"
    elif [[ -z "$feature" ]]; then
        result="FAIL (no/bad response)"
        failures=$((failures + 1))
    else
        result="FAIL (want $want_feature/$want_action)"
        failures=$((failures + 1))
    fi

    printf '%-42s %-22s %-6s %-14s %-7s %s\n' \
        "$query" "$feature" "$conf" "$action" "$params" "$result"
done

total=${#CASES[@]}
printf '\n%d/%d routing assertions passed\n' "$((total - failures))" "$total"

# ---------------------------------------------------------------------------
# Optional: did those decisions actually reach Loki?
# ---------------------------------------------------------------------------
if [[ "$CHECK_LOKI" == true ]]; then
    printf '\nChecking the log pipeline (Loki) for the %d extraction decisions...\n' \
        "${#LOGGED_QUERIES[@]}"

    kubectl port-forward -n istio-system "svc/loki" "$LOKI_PORT:3100" >/dev/null 2>&1 &
    pf_pid=$!
    # An orphaned port-forward silently breaks the next run's binding.
    trap 'kill "$pf_pid" 2>/dev/null || true' EXIT

    ready=false
    for _ in $(seq 1 15); do
        if curl -sf "http://localhost:$LOKI_PORT/ready" -m 2 >/dev/null 2>&1; then
            ready=true
            break
        fi
        sleep 1
    done
    if [[ "$ready" != true ]]; then
        echo "could not reach Loki on localhost:$LOKI_PORT — is the addon installed? (MONITORING.md 9)" >&2
        exit 1
    fi

    # Pull the whole window once and match locally. Doing it per-query in LogQL
    # would mean escaping regex metacharacters and an apostrophe into a filter.
    logs=""
    missing=()
    for _ in $(seq 1 7); do
        logs=$(curl -sS -G "http://localhost:$LOKI_PORT/loki/api/v1/query_range" \
            --data-urlencode 'query={namespace="default", container="backend"}' \
            --data-urlencode "start=${START_EPOCH}000000000" \
            --data-urlencode "end=$(date +%s)000000000" \
            --data-urlencode "limit=5000" -m 20 \
            | python3 -c '
import json, sys
try:
    d = json.load(sys.stdin)
except Exception:
    sys.exit(0)
for stream in d.get("data", {}).get("result", []):
    for _ts, line in stream.get("values", []):
        print(line)
')
        missing=()
        for query in "${LOGGED_QUERIES[@]}"; do
            if ! printf '%s' "$logs" | grep -qF "query='$query' ->"; then
                missing+=("$query")
            fi
        done
        [[ ${#missing[@]} -eq 0 ]] && break
        # Alloy tails through the Kubernetes API, so lines land a beat behind
        # the HTTP response.
        sleep 3
    done

    for query in "${LOGGED_QUERIES[@]}"; do
        status="FOUND"
        for m in ${missing+"${missing[@]}"}; do
            [[ "$m" == "$query" ]] && status="MISSING"
        done
        printf '%-42s %s\n' "$query" "$status"
    done

    found=$(( ${#LOGGED_QUERIES[@]} - ${#missing[@]} ))
    printf '\n%d/%d extraction decisions found in Loki\n' "$found" "${#LOGGED_QUERIES[@]}"

    if [[ ${#missing[@]} -eq ${#LOGGED_QUERIES[@]} ]]; then
        echo
        echo "Nothing at all was found. The usual cause is the backend being back at INFO:" >&2
        echo "  check LOGGING_LEVEL_COM_BANK_INTUITIVESEARCH in backend/k8s/configmap.yaml" >&2
    fi
    failures=$((failures + ${#missing[@]}))
fi

exit $(( failures > 0 ? 1 : 0 ))
