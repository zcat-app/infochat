#!/bin/bash

# D75 backend no-retention acceptance probe (M1-802 acceptance item 5):
# one full canary job against the M1-797 container, then assert /history
# prompt-free, no leftover output files, stdout prompt-free. Exit 0 = PASS.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

CANARY="infochat-canary-$(date +%s)-$$"
COMPOSE=(docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.comfyui.yml")

echo "== M1-802 D75 no-retention probe (canary: $CANARY)"
echo "   note: force-recreates the comfyui container with a 1-minute janitor"
echo "   window; model assets must exist (wizard step or INFOCHAT_COMFYUI_MODELS_DIR)"

INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES=1 \
INFOCHAT_COMFYUI_MODELS_DIR="${INFOCHAT_COMFYUI_MODELS_DIR:-infochat-comfyui-models}" \
    "${COMPOSE[@]}" up -d --force-recreate comfyui

echo "== waiting for the backend to answer /system_stats"
for _ in $(seq 1 60); do
    if "${COMPOSE[@]}" exec -T comfyui curl -fsS -m 2 http://127.0.0.1:8188/system_stats >/dev/null 2>&1; then
        break
    fi
    sleep 4
done
"${COMPOSE[@]}" exec -T comfyui curl -fsS http://127.0.0.1:8188/system_stats >/dev/null \
    || { echo "FAIL: backend never became healthy"; exit 1; }

CONTAINER="$("${COMPOSE[@]}" ps -q comfyui)"
docker cp "$REPO_ROOT/prod/config/comfyui-workflow.json" "$CONTAINER:/tmp/infochat-probe-workflow.json"

echo "== running one full job with the canary prompt"
"${COMPOSE[@]}" exec -T -e INFOCHAT_PROBE_CANARY="$CANARY" comfyui python3 - <<'EOF'
import json, os, time, urllib.parse, urllib.request

CANARY = os.environ["INFOCHAT_PROBE_CANARY"]
BASE = "http://127.0.0.1:8188"
CAP = 16 * 1024 * 1024

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return r.status, r.read()

graph = json.load(open("/tmp/infochat-probe-workflow.json"))
graph["4"]["inputs"]["text"] = CANARY
graph["7"]["inputs"]["seed"] = int(time.time() * 1000) % (2 ** 63)

status, body = call("POST", "/prompt", {"prompt": graph})
assert status == 200, f"submit failed: {status}"
prompt_id = json.loads(body)["prompt_id"]

deadline = time.time() + 300
entry = None
while time.time() < deadline:
    history = json.loads(call("GET", "/history/" + prompt_id)[1])
    if prompt_id in history:
        entry = history[prompt_id]
        break
    time.sleep(0.5)
assert entry is not None, "job never completed within 300s"
assert entry["status"]["status_str"] == "success", f"job status: {entry['status']}"
assert CANARY in json.dumps(entry), "submitted graph must hold the prompt while the job lives"

images = [img for out in entry["outputs"].values() for img in out.get("images", [])]
assert images, "completed job reported no output image"
image = images[0]

status, pixels = call("GET", "/view?filename=" + urllib.parse.quote(image["filename"])
                      + "&type=output&subfolder=" + urllib.parse.quote(image["subfolder"]))
assert status == 200 and pixels[:4] == b"\x89PNG", "fetched output is not a PNG"
assert len(pixels) <= CAP, "fetched output exceeds the byte cap"
assert len(pixels) > 10_000, "fetched output implausibly small"
print(f"   job completed; fetched {len(pixels)} bytes")

status, _ = call("POST", "/history", {"delete": [prompt_id]})
assert status == 200, "history clear failed"

status, body = call("GET", "/history")
assert CANARY not in body.decode("utf-8", "replace"), \
    "GET /history still contains the prompt text after the clear"
assert prompt_id not in json.loads(body), "the job's history entry survived the clear"
print("   history cleared; no prompt text retrievable")
EOF

echo "== waiting for the janitor window to sweep the output and temp dirs"
deadline=$((SECONDS + 300))
while [ "$SECONDS" -lt "$deadline" ]; do
    leftover="$("${COMPOSE[@]}" exec -T comfyui sh -c \
        'find /opt/ComfyUI/output /opt/ComfyUI/temp -type f 2>/dev/null | wc -l')"
    [ "$leftover" -eq 0 ] && break
    sleep 10
done
[ "$leftover" -eq 0 ] \
    || { echo "FAIL: leftover backend files after the janitor window: $leftover"; exit 1; }
echo "   output and temp dirs empty again"

if "${COMPOSE[@]}" logs comfyui | grep -F "$CANARY" >/dev/null; then
    echo "FAIL: container stdout contains the canary prompt"
    exit 1
fi
echo "   container stdout is prompt-free"

echo "PASS: D75 backend no-retention end state holds (history cleared, no leftover files, stdout prompt-free)"
