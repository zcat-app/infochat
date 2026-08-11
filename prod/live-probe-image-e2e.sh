#!/bin/bash

# Image e2e release gate (M1-816): the CONFIGURED pipeline proven against
# the deployment's own backend + runtime template — mocked graph-shape
# tests alone do not approve image delivery (analysis P13; E8/E10).

# Layer-4 shape (verification.md §Test layers): NOT part of mvn verify —
# it needs the GPU backend. Run on the deployment host; exit 0 = gate
# passes. Canary discipline per D75: never a real user prompt.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

RUNTIME_PROPS="$REPO_ROOT/prod/runtime/application.properties"
SHIPPED_PROPS="$REPO_ROOT/infochat-provider/src/main/resources/application.properties"
TEMPLATE="$REPO_ROOT/prod/runtime/comfyui-workflow.json"
COMPOSE=(docker compose -f "$REPO_ROOT/docker-compose.yml" -f "$REPO_ROOT/docker-compose.comfyui.yml")
# Deployment checkouts carry the wizard's secrets.env; compose must read it
# (empty pass-through defaults must resolve to the real credentials).
[ -f "$REPO_ROOT/prod/runtime/secrets.env" ] \
    && COMPOSE+=(--env-file "$REPO_ROOT/prod/runtime/secrets.env")
LOCAL_BASE_URL="http://comfyui:8188"

[ -f "$RUNTIME_PROPS" ] \
    || { echo "FAIL: $RUNTIME_PROPS absent — run wizard step 4b first"; exit 1; }
[ -f "$TEMPLATE" ] \
    || { echo "FAIL: $TEMPLATE absent — no configured runtime template"; exit 1; }

read_prop() {
    sed -n "s/^$2=//p" "$1" | tail -n 1
}

BASE_URL="$(read_prop "$RUNTIME_PROPS" 'infochat.image.base-url')"
[ -n "$BASE_URL" ] \
    || { echo "FAIL: infochat.image.base-url unset in $RUNTIME_PROPS — /image not enabled"; exit 1; }

CEILING="$(read_prop "$RUNTIME_PROPS" 'infochat.image.max-output-pixels')"
CEILING_SOURCE="runtime override"
if [ -z "$CEILING" ]; then
    [ -f "$SHIPPED_PROPS" ] \
        || { echo "FAIL: $SHIPPED_PROPS absent and no runtime ceiling override"; exit 1; }
    CEILING="$(read_prop "$SHIPPED_PROPS" 'infochat.image.max-output-pixels')"
    CEILING_SOURCE="shipped default"
fi
[ -n "$CEILING" ] \
    || { echo "FAIL: infochat.image.max-output-pixels unset in runtime and shipped properties"; exit 1; }

CANARY="infochat-canary-$(date +%s)-$$"
echo "== M1-816 image e2e release gate (canary: $CANARY)"
echo "   backend:  $BASE_URL"
echo "   ceiling:  $CEILING px ($CEILING_SOURCE)"
echo "   template: $TEMPLATE"

if [[ "$BASE_URL" == "$LOCAL_BASE_URL" ]]; then
    MODE=local
    # M1-802 probe mechanics, reused unreshaped: force-recreate with a
    # 1-minute janitor so the leftover check completes inside the probe;
    # the models dir keeps compose's own layering, never forced here.
    echo "== local compose backend: force-recreating comfyui with a 1-minute janitor"
    INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES=1 \
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
    docker cp "$TEMPLATE" "$CONTAINER:/tmp/infochat-probe-template.json"
    RUN_PYTHON=("${COMPOSE[@]}" exec -T
        -e INFOCHAT_PROBE_CANARY="$CANARY"
        -e INFOCHAT_PROBE_CEILING="$CEILING"
        -e INFOCHAT_PROBE_BASE="http://127.0.0.1:8188"
        -e INFOCHAT_PROBE_TEMPLATE="/tmp/infochat-probe-template.json"
        comfyui python3 -)
else
    MODE=remote
    echo "== remote backend: probing $BASE_URL directly from the host"
    curl -fsS -m 5 "$BASE_URL/system_stats" >/dev/null \
        || { echo "FAIL: backend not reachable at $BASE_URL"; exit 1; }
    RUN_PYTHON=(env
        INFOCHAT_PROBE_CANARY="$CANARY"
        INFOCHAT_PROBE_CEILING="$CEILING"
        INFOCHAT_PROBE_BASE="$BASE_URL"
        INFOCHAT_PROBE_TEMPLATE="$TEMPLATE"
        python3 -)
fi

"${RUN_PYTHON[@]}" <<'EOF'
import json, math, os, random, struct, time, urllib.error, urllib.parse, urllib.request

CANARY = os.environ["INFOCHAT_PROBE_CANARY"]
CEILING = int(os.environ["INFOCHAT_PROBE_CEILING"])
BASE = os.environ["INFOCHAT_PROBE_BASE"]
TEMPLATE_PATH = os.environ["INFOCHAT_PROBE_TEMPLATE"]
CAP = 16 * 1024 * 1024
MAX_EDGE = 4096

def call(method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method=method,
                                 headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=300) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()

def ihdr(pixels):
    assert pixels[:8] == b"\x89PNG\r\n\x1a\n", "fetched output is not a PNG"
    return struct.unpack(">II", pixels[16:24])

def round16(value):
    # ComfyUIClient.roundToSixteen: half-up to the nearest multiple of 16.
    return max(16, ((int(math.floor(value + 0.5)) + 8) // 16) * 16)

template = json.load(open(TEMPLATE_PATH))

# Node discovery mirrors ComfyUIClient's template validation: the single
# placeholder text slot, the numeric-seed KSampler, its latent_image
# link, and the single ImageScaleToTotalPixels fit node.
prompt_key = next(k for k, n in template.items()
                  if n.get("inputs", {}).get("text") == "INFOCHAT_PROMPT_PLACEHOLDER")
sampler_key = next(k for k, n in template.items()
                   if n.get("class_type") == "KSampler"
                   and isinstance(n.get("inputs", {}).get("seed"), (int, float)))
latent_key = template[sampler_key]["inputs"]["latent_image"][0]
fit_key = next(k for k, n in template.items()
               if n.get("class_type") == "ImageScaleToTotalPixels")
assert isinstance(template[latent_key]["inputs"].get("width"), int) \
    and isinstance(template[latent_key]["inputs"].get("height"), int), \
    "the latent node carries no numeric width/height budget"

def fresh_graph():
    graph = json.loads(json.dumps(template))
    graph[prompt_key]["inputs"]["text"] = CANARY
    graph[sampler_key]["inputs"]["seed"] = random.getrandbits(62)
    return graph

def converter_graph(target_w, target_h, with_crop):
    # Mirrors ComfyUIClient.buildGraph(prompt, w, h): latent dims = the
    # baked budget at the target ratio (/16), fit node swapped to an
    # exact-W/H ImageScale.
    graph = fresh_graph()
    latent = graph[latent_key]["inputs"]
    budget = latent["width"] * latent["height"]
    ratio = target_w / target_h
    latent["width"] = min(round16(math.sqrt(budget * ratio)), MAX_EDGE)
    latent["height"] = min(round16(math.sqrt(budget / ratio)), MAX_EDGE)
    fit = graph[fit_key]
    fit["class_type"] = "ImageScale"
    fit["inputs"].pop("megapixels", None)
    fit["inputs"].pop("resolution_steps", None)
    fit["inputs"]["width"] = target_w
    fit["inputs"]["height"] = target_h
    if with_crop:
        fit["inputs"]["crop"] = "disabled"
    return graph

def run_job(graph, label):
    status, body = call("POST", "/prompt", {"prompt": graph})
    assert status == 200, f"{label}: backend refused the graph: {status} {body[:400]!r}"
    prompt_id = json.loads(body)["prompt_id"]
    deadline = time.time() + 300
    entry = None
    while time.time() < deadline:
        history = json.loads(call("GET", "/history/" + prompt_id)[1])
        if prompt_id in history:
            entry = history[prompt_id]
            break
        time.sleep(0.5)
    assert entry is not None, f"{label}: job never completed within 300s"
    assert entry["status"]["status_str"] == "success", \
        f"{label}: job status: {entry['status']}"
    images = [img for out in entry["outputs"].values() for img in out.get("images", [])]
    assert images, f"{label}: completed job reported no output image"
    image = images[0]
    status, pixels = call("GET", "/view?filename=" + urllib.parse.quote(image["filename"])
                          + "&type=output&subfolder=" + urllib.parse.quote(image["subfolder"]))
    assert status == 200, f"{label}: output fetch failed: {status}"
    assert len(pixels) <= CAP, f"{label}: output exceeds the byte cap"
    dims = ihdr(pixels)
    status, _ = call("POST", "/history", {"delete": [prompt_id]})
    assert status == 200, f"{label}: history clear failed"
    return dims

width, height = run_job(fresh_graph(), "default job")
assert width * height <= CEILING, (f"default job: {width}x{height} = {width * height} px "
                                   f"exceeds the configured ceiling {CEILING} (the E8 gate)")
print(f"   default job: backend accepted the baked graph; {width}x{height} = "
      f"{width * height} px <= ceiling {CEILING}")

for target_w, target_h in ((600, 600), (1600, 900)):
    width, height = run_job(converter_graph(target_w, target_h, True),
                            f"-r {target_w}x{target_h}")
    assert (width, height) == (target_w, target_h), \
        f"-r {target_w}x{target_h}: delivered {width}x{height} (the E10 gate)"
    print(f"   -r {target_w}x{target_h}: backend accepted the converter graph; exact dimensions")

status, body = call("POST", "/prompt", {"prompt": converter_graph(600, 600, False)})
assert status != 200, ("negative baseline: the crop-less graph was ACCEPTED — "
                       "the probe cannot discriminate acceptance from rejection")
assert b"crop" in body.lower(), \
    f"negative baseline: refusal does not name the crop input: {body[:400]!r}"
print("   negative baseline: the crop-less graph is refused, naming the crop input")

status, body = call("GET", "/history")
assert CANARY not in body.decode("utf-8", "replace"), \
    "GET /history still contains the canary after the per-job clears"
print("   history is prompt-free after every job")
EOF

if [[ "$MODE" == local ]]; then
    echo "== waiting for the janitor window to sweep the output and temp dirs"
    deadline=$((SECONDS + 300))
    leftover=1
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
else
    echo "   NOTE: leftover-file and container-stdout checks need the local compose"
    echo "   backend; this deployment configured a remote backend, so retention"
    echo "   acceptance stays owned by live-probe-comfyui-no-retention.sh there."
fi

echo "PASS: configured image pipeline proven against the deployment's own backend"
echo "      (default ceiling, exact -r dimensions, crop-less refusal, canary hygiene)"
