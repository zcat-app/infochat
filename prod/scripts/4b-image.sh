#!/bin/bash

# wizard step 4b: optionally provision the /image backend (D73/D77) and write
# infochat.image.* into the runtime application.properties (§7.7.2). Operator
# docs: SETUP_GUIDE.md "Step 4b — Image generation".

# Modes: local = download ONE curated model into the models volume, bring up
# the docker-compose.comfyui.yml overlay, and point base-url at the compose
# name comfyui:8188 (no host port — D77 one-box, llamacpp item-8 precedent).

# Modes cont.: remote = point base-url at a ComfyUI on a second, operator-owned
# GPU box (D77 two-box) and print the firewall requirement; none = leave
# base-url unset so /image does not exist (D73, absent not disabled).

# Local install is offered only on GPU-capable profiles (laptop, remote-llm);
# pi/vps get remote or none (no usable GPU — analysis P22).

# Local is ROCm-only, validated on Strix Halo (gfx1151) alone. Model switch =
# re-run this step (design addendum): recreate, offer to delete old files.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROD_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROD_DIR/.." && pwd)"
RUNTIME_DIR="${INFOCHAT_RUNTIME_DIR:-$PROD_DIR/runtime}"
CONFIG_FILE="$RUNTIME_DIR/application.properties"
SECRETS_FILE="$RUNTIME_DIR/secrets.env"
COMPOSE_FILE="$REPO_ROOT/docker-compose.yml"
COMFYUI_COMPOSE_FILE="$REPO_ROOT/docker-compose.comfyui.yml"
# Host-side template file; the Provider reads it through its own bind mount at
# /app/comfyui-workflow.json (docker-compose.yml, §7.7.2 runtime-config shape).
WORKFLOW_FILE="$RUNTIME_DIR/comfyui-workflow.json"
WORKFLOW_FILE_IN_CONTAINER="/app/comfyui-workflow.json"
MODELS_VOLUME="infochat-comfyui-models"
# One-shot image used to populate the models volume; pinned per the M1-004
# tag-pinning precedent (same image 4-llm.sh's fetch_gguf uses).
CURL_IMAGE="curlimages/curl:8.11.1"
BASE_URL_LOCAL="http://comfyui:8188"
WAIT_TIMEOUT=180

DEFAULT_MODEL="mage_bf16"
DEFAULT_MODE="none"
VALID_MODES="local remote none"

# Curated model picker — exactly three models x two tiers, hardcoded (design
# addendum: never a live HuggingFace listing, whose nvfp4/mxfp8 builds and
# Krea `raw` variants would mislead).

# Latency is the 2026-08-09 spike's CONTAINER steady state (design doc
# "Measurement results 2026-08-09" table) — never a conda number (P22).
# Disk is the design doc's footprint table.
MODEL_OPTIONS="mage_bf16 mage_small zimage_bf16 zimage_small krea_bf16 krea_small"
declare -A MODEL_LABEL=(
  [mage_bf16]="Mage-Flow Turbo  — Recommended (bf16)"
  [mage_small]="Mage-Flow Turbo  — Smaller footprint (int8 checkpoint)"
  [zimage_bf16]="Z-Image Turbo   — Recommended (bf16)"
  [zimage_small]="Z-Image Turbo   — Smaller footprint (int8 + fp8 encoder)"
  [krea_bf16]="Krea 2 Turbo    — Recommended (bf16)"
  [krea_small]="Krea 2 Turbo    — Smaller footprint (int8 + fp8 encoder)"
)
declare -A MODEL_LATENCY_S=(
  [mage_bf16]=4.07 [mage_small]=4.07
  [zimage_bf16]=22.37 [zimage_small]=22.37
  [krea_bf16]=22.41 [krea_small]=22.41
)
declare -A MODEL_SETTING_DISPLAY=(
  [mage_bf16]="4 st @ 1024" [mage_small]="4 st @ 1024"
  [zimage_bf16]="8 st @ 1024" [zimage_small]="8 st @ 1024"
  [krea_bf16]="6 st @ 0.6 MP" [krea_small]="6 st @ 0.6 MP"
)
declare -A MODEL_DISK_DISPLAY=(
  [mage_bf16]="~16.5 GB" [mage_small]="~13 GB"
  [zimage_bf16]="~20 GB" [zimage_small]="~11.5 GB"
  [krea_bf16]="~34.5 GB" [krea_small]="~20 GB"
)
# Integer GB (rounded up) for the disk/memory preflight comparison.
declare -A MODEL_DISK_REQ_GB=(
  [mage_bf16]=17 [mage_small]=13
  [zimage_bf16]=20 [zimage_small]=12
  [krea_bf16]=35 [krea_small]=20
)

# D78 translate-prompt recommendation per model: Krea's encoder holds
# non-English prompts directly (measured); mage/zimage keep the leg. The
# ask defaults to this table; an absent model gets translate recommended.
declare -A MODEL_TRANSLATE_RECOMMENDED=(
  [mage_bf16]=true [mage_small]=true
  [zimage_bf16]=true [zimage_small]=true
  [krea_bf16]=false [krea_small]=false
)

# Asset locations — HuggingFace resolve URLs, hardcoded per tier (design
# addendum; the preflight HEAD-check catches a repo restructure). Subdirs are
# ComfyUI's model folders: diffusion_models/, text_encoders/, vae/.

# The qwen3vl_4b bf16 encoder is the SAME blob in the Mage-Flow and Krea-2
# repos — the download dedupes it by skipping a file already present.
HF="https://huggingface.co"
MAGE_REPO="$HF/Comfy-Org/Mage-Flow/resolve/main"
ZIMAGE_REPO="$HF/Comfy-Org/z_image_turbo/resolve/main"
KREA_REPO="$HF/Comfy-Org/Krea-2/resolve/main"
declare -A MODEL_URL_CKPT=(
  [mage_bf16]="$MAGE_REPO/diffusion_models/mage_flow_turbo_bf16.safetensors"
  [mage_small]="$MAGE_REPO/diffusion_models/mage_flow_turbo_int8_convrot.safetensors"
  [zimage_bf16]="$ZIMAGE_REPO/split_files/diffusion_models/z_image_turbo_bf16.safetensors"
  [zimage_small]="$ZIMAGE_REPO/split_files/diffusion_models/z_image_turbo_int8_convrot.safetensors"
  [krea_bf16]="$KREA_REPO/diffusion_models/krea2_turbo_bf16.safetensors"
  [krea_small]="$KREA_REPO/diffusion_models/krea2_turbo_int8_convrot.safetensors"
)
declare -A MODEL_URL_ENCODER=(
  [mage_bf16]="$MAGE_REPO/text_encoders/qwen3vl_4b_bf16.safetensors"
  [mage_small]="$MAGE_REPO/text_encoders/qwen3vl_4b_bf16.safetensors"
  [zimage_bf16]="$ZIMAGE_REPO/split_files/text_encoders/qwen_3_4b.safetensors"
  [zimage_small]="$ZIMAGE_REPO/split_files/text_encoders/qwen_3_4b_fp8_mixed.safetensors"
  [krea_bf16]="$KREA_REPO/text_encoders/qwen3vl_4b_bf16.safetensors"
  [krea_small]="$KREA_REPO/text_encoders/qwen3vl_4b_fp8_scaled.safetensors"
)
declare -A MODEL_URL_VAE=(
  [mage_bf16]="$MAGE_REPO/vae/mage_flow_vae_bf16.safetensors"
  [mage_small]="$MAGE_REPO/vae/mage_flow_vae_bf16.safetensors"
  [zimage_bf16]="$ZIMAGE_REPO/split_files/vae/ae.safetensors"
  [zimage_small]="$ZIMAGE_REPO/split_files/vae/ae.safetensors"
  [krea_bf16]="$KREA_REPO/vae/qwen_image_vae.safetensors"
  [krea_small]="$KREA_REPO/vae/qwen_image_vae.safetensors"
)
declare -A MODEL_CKPT_FILE=(
  [mage_bf16]="mage_flow_turbo_bf16.safetensors"
  [mage_small]="mage_flow_turbo_int8_convrot.safetensors"
  [zimage_bf16]="z_image_turbo_bf16.safetensors"
  [zimage_small]="z_image_turbo_int8_convrot.safetensors"
  [krea_bf16]="krea2_turbo_bf16.safetensors"
  [krea_small]="krea2_turbo_int8_convrot.safetensors"
)
declare -A MODEL_ENCODER_FILE=(
  [mage_bf16]="qwen3vl_4b_bf16.safetensors"
  [mage_small]="qwen3vl_4b_bf16.safetensors"
  [zimage_bf16]="qwen_3_4b.safetensors"
  [zimage_small]="qwen_3_4b_fp8_mixed.safetensors"
  [krea_bf16]="qwen3vl_4b_bf16.safetensors"
  [krea_small]="qwen3vl_4b_fp8_scaled.safetensors"
)
declare -A MODEL_VAE_FILE=(
  [mage_bf16]="mage_flow_vae_bf16.safetensors"
  [mage_small]="mage_flow_vae_bf16.safetensors"
  [zimage_bf16]="ae.safetensors"
  [zimage_small]="ae.safetensors"
  [krea_bf16]="qwen_image_vae.safetensors"
  [krea_small]="qwen_image_vae.safetensors"
)

# Krea's two community VAE files (decision 5): the spacepxl 2x decode
# VAE and krea2RealVae, the recommended 1x decoder (decision 4). Both
# download for every Krea tier; the picker bakes ONE into the template.
KREA_2X_VAE_URL="$HF/spacepxl/Wan2.1-VAE-upscale2x/resolve/main/Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors"
KREA_2X_VAE_FILE="Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors"
KREA_REALVAE_URL="$HF/artsyww/KREA2REALVAE/resolve/main/krea2RealVae_v10.safetensors"
KREA_REALVAE_FILE="krea2RealVae_v10.safetensors"

# Per-model graph shape for the generated workflow template — the spike's
# measured graphs (design doc "Measurement results 2026-08-09"; P28).
# Mage-Flow needs the Flux2 latent (§B2).

# Krea 2 samples with the plain SDXL-shaped latent at its 0.6 MP budget
# (896x672). Steps are the Final decision 2 budgets (Mage 4 / Z-Image 8
# / Krea 6).
declare -A MODEL_LATENT=(
  [mage_bf16]="EmptyFlux2LatentImage" [mage_small]="EmptyFlux2LatentImage"
  [zimage_bf16]="EmptySD3LatentImage" [zimage_small]="EmptySD3LatentImage"
  [krea_bf16]="EmptyLatentImage" [krea_small]="EmptyLatentImage"
)
declare -A MODEL_CLIP_TYPE=(
  [mage_bf16]="mage" [mage_small]="mage"
  [zimage_bf16]="lumina2" [zimage_small]="lumina2"
  [krea_bf16]="krea2" [krea_small]="krea2"
)
declare -A MODEL_STEPS=(
  [mage_bf16]=4 [mage_small]=4
  [zimage_bf16]=8 [zimage_small]=8
  [krea_bf16]=6 [krea_small]=6
)
declare -A MODEL_LATENT_DIMS=(
  [mage_bf16]='"width": 1024, "height": 1024' [mage_small]='"width": 1024, "height": 1024'
  [zimage_bf16]='"width": 1024, "height": 1024' [zimage_small]='"width": 1024, "height": 1024'
  [krea_bf16]='"width": 896, "height": 672' [krea_small]='"width": 896, "height": 672'
)
# Baked lanczos exact-fit target (ImageScaleToTotalPixels megapixels):
# the decoded size, so the fit is exact — M1-803's converter takes this
# seam over for per-job --resolution targets.

# The node's "megapixels" unit is 1024x1024 pixels, so each value is the
# decoded pixel count / 1048576: 1024x1024 -> 1.0, 1792x1344 (Krea 2x)
# -> 2.296875, 896x672 (Krea 1x) -> 0.57421875.
declare -A MODEL_FIT_MP=(
  [mage_bf16]=1.0 [mage_small]=1.0
  [zimage_bf16]=1.0 [zimage_small]=1.0
  [krea_bf16]=2.296875 [krea_small]=2.296875
)

usage() {
  echo "Usage: 4b-image.sh [--defaults] [--dry-run] [--verbose] [-h|--help]"
  echo "  Optionally provision the /image generation backend (D73/D77) and write"
  echo "  infochat.image.* into the runtime application.properties."
  echo "  --defaults  take '$DEFAULT_MODE' (do not enable /image) without prompting —"
  echo "              the feature is optional and local install is a multi-GB download."
  echo "  --dry-run   print the profile gate and the curated model picker, then exit"
  echo "              without downloading or writing anything."
  echo "  --verbose   also print the picker's full detail (spike sourcing, latency"
  echo "              footnotes, disk arithmetic, hardware scope)."
}

defaults=0
dryrun=0
verbose=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help) usage; exit 0 ;;
    --defaults) defaults=1 ;;
    --dry-run) dryrun=1 ;;
    --verbose) verbose=1 ;;
    *) usage >&2; exit 2 ;;
  esac
  shift
done

# Idempotent property write: drop any existing line for the key, then append
# the new value (1-profile.sh / 4-llm.sh precedent) so a re-run replaces
# rather than duplicates each line.
set_prop() {
  local key="$1" value="$2"
  local escaped="${key//./\\.}"
  if [[ -f "$CONFIG_FILE" ]]; then
    sed -i "/^${escaped}=/d" "$CONFIG_FILE"
  fi
  printf '%s=%s\n' "$key" "$value" >> "$CONFIG_FILE"
}

# Remove every infochat.image.* line plus this step's 'not enabled' marker, so
# a re-run starts from a clean slate regardless of direction.
clear_image_props() {
  if [[ -f "$CONFIG_FILE" ]]; then
    sed -i -e '/^infochat\.image\./d' \
           -e '/^# infochat\.image: not enabled/d' "$CONFIG_FILE"
  fi
}

record_not_enabled() {
  clear_image_props
  printf '# infochat.image: not enabled (D73 — /image absent until base-url is set)\n' >> "$CONFIG_FILE"
}

umask 077
mkdir -p "$RUNTIME_DIR"

# Read the profile 1-profile.sh recorded; the local-install offer is
# profile-gated (never pi/vps — analysis P22).
if [[ ! -f "$CONFIG_FILE" ]]; then
  echo "FAIL: $CONFIG_FILE not found; run 1-profile.sh (wizard step 1) first." >&2
  exit 1
fi
profile="$(sed -n 's/^quarkus\.profile=//p' "$CONFIG_FILE" | tail -n1)"
if [[ -z "$profile" ]]; then
  echo "FAIL: quarkus.profile not set in $CONFIG_FILE; run 1-profile.sh first." >&2
  exit 1
fi

case "$profile" in
  laptop|remote-llm) local_offered=1 ;;
  pi|vps)            local_offered=0 ;;
  *) echo "FAIL: unknown profile '$profile' in $CONFIG_FILE" >&2; exit 1 ;;
esac

print_picker() {
  echo "Curated models — three models x two tiers, hardcoded; latency + disk measured, pre-commit."
  echo
  printf '  %-4s %-50s %-16s %-14s %s\n' "#" "Model" "Setting" "Latency" "Disk"
  local i=1 opt
  for opt in $MODEL_OPTIONS; do
    # The Z-Image steady-state marker rides inline in the row (M1-798 item 3).
    local latency="${MODEL_LATENCY_S[$opt]} s"
    if [[ "$opt" == zimage_* ]]; then
      latency="$latency (steady state)"
    fi
    printf '  %-4s %-50s %-16s %-14s %s\n' "$i)" "${MODEL_LABEL[$opt]}" "${MODEL_SETTING_DISPLAY[$opt]}" "$latency" "${MODEL_DISK_DISPLAY[$opt]}"
    i=$(( i + 1 ))
  done
  echo
  if [[ "$verbose" -eq 1 ]]; then
    echo "Curated models — three models x two tiers, hardcoded (never a raw repo"
    echo "listing). Latency: container-measured steady state (2026-08-09 spike,"
    echo "M1-797 protocol). Disk: measured checkpoint + encoder + VAE footprint,"
    echo "printed BEFORE you commit."
    echo
    echo "Z-Image's 22.37 s is the steady state — a first run takes ~28.6 s while"
    echo "kernels autotune (the probe warm-up absorbs that). Krea's number is the"
    echo "0.6 MP sampling budget; its decode stage delivers 1792x1344 (~2.4 MP)."
    echo
    echo "Per-model disk (bf16 tier): Mage-Flow 7.7 + 8.3 (qwen3vl_4b) + 0.33 = ~16.5 GB;"
    echo "Z-Image 12 + 7.5 (qwen_3_4b) + 0.32 = ~20 GB; Krea 2 25 + 8.3 (qwen3vl_4b)"
    echo "+ 0.24 (stock VAE) + 0.51 (krea2RealVae) + 0.51 (Wan2.1 2x VAE) = ~34.5 GB."
    echo "Smaller-footprint tier: ~13 / ~11.5 / ~20 GB — same speed on this hardware"
    echo "(quantization buys no speed on gfx1151), slight quality cost. The qwen3vl_4b"
    echo "encoder is shared between Mage-Flow and Krea 2 (identical blob) — installing"
    echo "both downloads it once."
    echo
    echo "Hardware scope: the LOCAL container path is ROCm-only and validated on"
    echo "Strix Halo (gfx1151) alone — other ROCm GPUs are unverified and NVIDIA is"
    echo "not covered by the overlay."
  fi
  echo "Hardware scope: LOCAL is ROCm-only, validated on Strix Halo (gfx1151) alone — NVIDIA not covered."
  echo
  echo "Detail (spike sourcing, latency footnotes, disk arithmetic): run with --verbose."
}

print_gate() {
  echo "Profile '$profile':"
  if [[ "$local_offered" -eq 1 ]]; then
    echo "  offered: local install | remote (two-box) | not enabled"
  else
    echo "  offered: remote (two-box) | not enabled"
    echo "  (no local install on '$profile' — no usable GPU)"
  fi
}

# D78: per-model recommendation (MODEL_TRANSLATE_RECOMMENDED); bare Enter
# and --defaults take it. A re-run re-asks, so a model switch rewrites the
# key — a stale value never survives.
translate_prompt="true"
choose_translate_prompt() {
  local rec="${MODEL_TRANSLATE_RECOMMENDED[$model]:-true}"
  if [[ "$defaults" -eq 1 ]]; then
    translate_prompt="$rec"
    echo "taking translation recommendation for $model: translate-prompt=$rec"
    return 0
  fi
  local choice
  read -rp "Translate /image prompts to English before generation? (yes|no) [$rec]: " choice
  choice="${choice:-$rec}"
  case "$choice" in
    yes|y|true) translate_prompt="true" ;;
    no|n|false) translate_prompt="false" ;;
    *) echo "FAIL: translation choice must be yes or no (got '$choice')." >&2; exit 1 ;;
  esac
}

print_translate_recommendations() {
  echo "infochat.image.translate-prompt recommendation per model (D78):"
  local m
  for m in $MODEL_OPTIONS; do
    echo "  $m: translate-prompt=${MODEL_TRANSLATE_RECOMMENDED[$m]:-true}"
  done
}

if [[ "$dryrun" -eq 1 ]]; then
  print_gate
  echo
  print_picker
  echo
  print_translate_recommendations
  exit 0
fi

# Models-volume layout: the overlay's default is the named volume; an operator
# who set INFOCHAT_COMFYUI_MODELS_DIR to a host path chose the host-dir layout
# (docker-compose.comfyui.yml header) and this step downloads there instead.
if [[ -z "${INFOCHAT_COMFYUI_MODELS_DIR:-}" && -f "$SECRETS_FILE" ]]; then
  INFOCHAT_COMFYUI_MODELS_DIR="$(sed -n 's/^INFOCHAT_COMFYUI_MODELS_DIR=//p' "$SECRETS_FILE" | tail -n1 | tr -d '"')"
fi
MODELS_HOST_DIR=""
case "${INFOCHAT_COMFYUI_MODELS_DIR:-}" in
  /*) MODELS_HOST_DIR="${INFOCHAT_COMFYUI_MODELS_DIR}" ;;
esac

compose_comfyui() {
  local env_args=()
  [[ -f "$SECRETS_FILE" ]] && env_args=(--env-file "$SECRETS_FILE")
  docker compose -f "$COMPOSE_FILE" -f "$COMFYUI_COMPOSE_FILE" "${env_args[@]}" "$@"
}

comfyui_running() {
  [[ -n "$(compose_comfyui ps -q comfyui 2>/dev/null || true)" ]]
}

# Stop + remove the ComfyUI container when /image is disabled; silent when
# there is nothing to stop (the reset-silence posture, M1-464).
stop_comfyui_if_present() {
  if comfyui_running; then
    echo "+ stop and remove the comfyui container"
    compose_comfyui rm -fs comfyui >/dev/null
  fi
}

# Offer to reclaim the PREVIOUS model's files on a switch (P24: model
# files are operator assets that persist; the offer names previous files
# only and skips every blob the new install shares).

# $1 = previous model, $2 = the new install's model or "none" — a remote
# new install shares no blobs, so every previous file is reclaimable. A
# disk-reclaim convenience, never part of the D75 no-content chain.
offer_delete_previous_files() {
  local prev="$1" new="$2"
  local prev_files new_files del_prev f n skip
  prev_files=("diffusion_models/${MODEL_CKPT_FILE[$prev]}"
              "text_encoders/${MODEL_ENCODER_FILE[$prev]}"
              "vae/${MODEL_VAE_FILE[$prev]}")
  new_files=()
  if [[ "$new" != "none" ]]; then
    new_files=("diffusion_models/${MODEL_CKPT_FILE[$new]}"
               "text_encoders/${MODEL_ENCODER_FILE[$new]}"
               "vae/${MODEL_VAE_FILE[$new]}")
  fi
  # Krea's two community VAE files are shared across Krea tiers.
  if [[ "$prev" == krea_* ]]; then
    prev_files+=("vae/${KREA_REALVAE_FILE}" "vae/${KREA_2X_VAE_FILE}")
  fi
  if [[ "$new" == krea_* ]]; then
    new_files+=("vae/${KREA_REALVAE_FILE}" "vae/${KREA_2X_VAE_FILE}")
  fi
  echo
  echo "The previous model's files (${prev}) are still in the models"
  echo "store. Delete them to reclaim ${MODEL_DISK_DISPLAY[$prev]}?"
  echo "(removes ONLY the previous model's checkpoint/encoder/VAE files)"
  if [[ "$defaults" -eq 0 ]]; then
    read -rp "Delete previous model files? (yes|no) [no]: " del_prev
    del_prev="${del_prev:-no}"
  else
    del_prev="no"
  fi
  if [[ "$del_prev" != "yes" ]]; then
    echo "keeping previous model files."
    return 0
  fi
  for f in "${prev_files[@]}"; do
    # Never delete a blob the NEW install also uses (shared encoder/VAE).
    skip=0
    for n in "${new_files[@]}"; do
      [[ "$f" == "$n" ]] && skip=1
    done
    [[ "$skip" -eq 1 ]] && continue
    echo "+ remove $f from the models store"
    if [[ -n "$MODELS_HOST_DIR" ]]; then
      rm -f "$MODELS_HOST_DIR/$f"
    else
      docker run --rm -u 0:0 -v "$MODELS_VOLUME:/models" --entrypoint rm "$CURL_IMAGE" -f "/models/$f" || true
    fi
  done
}

# Detect an existing install so a re-run becomes the model-switch/edit path
# (design addendum: model switching is an operator operation, via re-run).
existing_url="$(sed -n 's/^infochat\.image\.base-url=//p' "$CONFIG_FILE" | tail -n1 || true)"
existing_model="$(sed -n 's/^infochat\.image\.model=//p' "$CONFIG_FILE" | tail -n1 || true)"
rerun_action=""
if [[ -n "$existing_url" ]]; then
  echo "Existing /image install detected:"
  echo "  base-url: $existing_url"
  echo "  model:    ${existing_model:-<unknown>}"
  echo
  if [[ "$defaults" -eq 0 ]]; then
    read -rp "Re-run action (keep|switch|disable) [keep]: " rerun_action
    rerun_action="${rerun_action:-keep}"
  else
    rerun_action="keep"
  fi
  case "$rerun_action" in
    keep)
      echo "keeping existing /image install; nothing to do."
      exit 0
      ;;
    disable)
      record_not_enabled
      stop_comfyui_if_present
      echo "disabled: infochat.image.* cleared — /image no longer exists (D73)."
      echo "Downloaded model files in the $MODELS_VOLUME volume were KEPT (operator"
      echo "assets); 'docker volume rm $MODELS_VOLUME' reclaims the disk."
      exit 0
      ;;
    switch)
      : # fall through to mode + picker below
      ;;
    *)
      echo "FAIL: unknown re-run action '$rerun_action' (expected: keep|switch|disable)" >&2
      exit 1
      ;;
  esac
fi

print_gate
echo
if [[ "$defaults" -eq 1 ]]; then
  mode="$DEFAULT_MODE"
  echo "--defaults: mode=$mode (/image is optional; not enabling by default)"
elif [[ "$local_offered" -eq 1 ]]; then
  read -rp "Enable /image generation? (${VALID_MODES// /|}) [$DEFAULT_MODE]: " mode
  mode="${mode:-$DEFAULT_MODE}"
else
  read -rp "Enable /image generation? (remote|none) [$DEFAULT_MODE]: " mode
  mode="${mode:-$DEFAULT_MODE}"
fi
case " $VALID_MODES " in
  *" $mode "*) ;;
  *) echo "FAIL: unknown mode '$mode' (expected: $VALID_MODES)" >&2; exit 1 ;;
esac
if [[ "$mode" == "local" && "$local_offered" -eq 0 ]]; then
  echo "FAIL: profile '$profile' cannot run a local image backend (no usable GPU); choose remote or none." >&2
  exit 1
fi

choose_model() {
  print_picker
  echo
  if [[ "$defaults" -eq 1 ]]; then
    model="$DEFAULT_MODEL"
    echo "taking default model: $model"
    return 0
  fi
  local choice
  read -rp "Model (1-6) [1 = $DEFAULT_MODEL]: " choice
  choice="${choice:-1}"
  if ! [[ "$choice" =~ ^[1-6]$ ]]; then
    echo "FAIL: model choice must be 1-6 (got '$choice')." >&2
    exit 1
  fi
  model="$(printf '%s\n' $MODEL_OPTIONS | sed -n "${choice}p")"
}

# Krea only: the template bakes ONE decode stage (D-3 resolution, user
# decision 2026-08-09). Default is the spacepxl 2x stage (decision 5);
# the decision 4 decoders are the 1x alternatives.

# The stage node is VAEUtils_VAEDecodeTiled for every choice — its
# upscale=-1 auto-detects the VAE's native factor (2x for the 12-channel
# Wan2.1 head, 1x for 3-channel decoders; node source at the pinned commit).
krea_vae=""
choose_krea_decode() {
  echo
  echo "Krea decode pipeline (baked into the template):"
  echo "  1) spacepxl 2x decode — 1792x1344 from the 0.6 MP sample, ~22.7 s (default)"
  echo "  2) krea2RealVae 1x — RECOMMENDED decoder: visibly crisper than stock, no tint"
  echo "  3) stock qwen_image_vae 1x — FALLBACK decoder: right choice for text-heavy renders"
  local choice
  if [[ "$defaults" -eq 1 ]]; then
    choice=1
    echo "taking default decode pipeline: spacepxl 2x"
  else
    read -rp "Decode pipeline (1-3) [1]: " choice
    choice="${choice:-1}"
  fi
  case "$choice" in
    1) krea_vae="$KREA_2X_VAE_FILE" ;;
    2) krea_vae="$KREA_REALVAE_FILE" ;;
    3) krea_vae="qwen_image_vae.safetensors" ;;
    *) echo "FAIL: decode pipeline choice must be 1-3 (got '$choice')." >&2; exit 1 ;;
  esac
}

# Krea only: the two community VAE weights are licence-UNDECLARED-class
# assets (P27) — disclose them BEFORE the download commits. Labels state
# what the HuggingFace cards say; never claim a licence the card lacks.
print_krea_asset_licences() {
  echo
  echo "COMMUNITY ASSET LICENCES (Krea downloads these two VAE files):"
  echo "  - krea2RealVae_v10.safetensors (artsyww/KREA2REALVAE) — licence UNDECLARED on the HF card."
  echo "  - Wan2.1_VAE_upscale2x_imageonly_real_v1.safetensors (spacepxl/Wan2.1-VAE-upscale2x) —"
  echo "    card declares Apache-2.0 (was UNDECLARED at the spike; verify before redistributing)."
  echo "  The ComfyUI-VAE-Utils decode node is MIT, baked into the ComfyUI image."
}

# Preflight one URL with a HEAD request (analysis P22: HEAD-check every asset
# URL BEFORE any download, so a dead link — e.g. a repo restructure — aborts
# with nothing fetched).
head_check() {
  local url="$1"
  echo "+ HEAD $url"
  if ! curl -fsSLI -o /dev/null --max-time 60 "$url"; then
    echo "FAIL: cannot reach $url over the host's own network path (the path the download uses) — aborting BEFORE any download." >&2
    echo "      Check host connectivity: VPN, proxy, or firewall. If you use a proxy, export" >&2
    echo "      HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY (the download uses them) and re-run." >&2
    exit 1
  fi
}

avail_gb() {
  local path="$1" kb
  kb="$(df -Pk "$path" 2>/dev/null | awk 'NR==2 {print $4}')"
  if [[ -z "$kb" ]]; then
    echo ""
    return 0
  fi
  echo $(( kb / 1024 / 1024 ))
}

require_disk_gb() {
  local need_gb="$1" check_path avail
  if [[ -n "$MODELS_HOST_DIR" ]]; then
    check_path="$MODELS_HOST_DIR"
  else
    check_path="$(docker info --format '{{.DockerRootDir}}' 2>/dev/null || true)"
    [[ -z "$check_path" ]] && check_path="$RUNTIME_DIR"
  fi
  avail="$(avail_gb "$check_path")"
  if [[ -z "$avail" ]]; then
    echo "WARN: could not determine free disk at $check_path — skipping the disk check." >&2
    return 0
  fi
  echo "+ disk check: need ~${need_gb} GB, ${avail} GB available at $check_path"
  if (( avail < need_gb )); then
    echo "FAIL: insufficient disk for the chosen tier (~${need_gb} GB needed, ${avail} GB available)." >&2
    exit 1
  fi
}

# On the validated ROCm shape (Strix Halo, unified memory) the resident model
# is GTT memory charged to system RAM (docker-compose.comfyui.yml sizing
# basis), so available memory is the VRAM proxy.
require_mem_gb() {
  local need_gb="$1" avail_kb avail_gb
  avail_kb="$(awk '/MemAvailable/ {print $2}' /proc/meminfo)"
  avail_gb=$(( avail_kb / 1024 / 1024 ))
  echo "+ memory/VRAM check: need ~${need_gb} GB, ${avail_gb} GB available"
  if (( avail_gb < need_gb )); then
    echo "FAIL: insufficient memory/VRAM for the chosen tier (~${need_gb} GB needed, ${avail_gb} GB available)." >&2
    exit 1
  fi
}

# Fetch one asset into the models dir. Presence check first — that is also the
# qwen3vl_4b dedupe between Mage-Flow and Krea 2 (identical blob, downloaded
# once; fetch_gguf's skip-if-present precedent).
fetch_asset() {
  local url="$1" dest="$2"
  if [[ -n "$MODELS_HOST_DIR" ]]; then
    if [[ -f "$MODELS_HOST_DIR/$dest" ]]; then
      echo "skip download ($dest already present)"
      return 0
    fi
    echo "+ download $url -> $MODELS_HOST_DIR/$dest"
    mkdir -p "$MODELS_HOST_DIR/$(dirname "$dest")"
    if ! curl -fL --retry 3 -o "$MODELS_HOST_DIR/$dest" "$url"; then
      echo "FAIL: download of $url failed over the host's own network path (the path the preflight checked)." >&2
      echo "      Check host connectivity: VPN, proxy, or firewall. If you use a proxy, export" >&2
      echo "      HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY (the download uses them) and re-run." >&2
      exit 1
    fi
    return 0
  fi
  if docker run --rm -v "$MODELS_VOLUME:/models" --entrypoint ls "$CURL_IMAGE" "/models/$dest" >/dev/null 2>&1; then
    echo "skip download ($dest already present)"
    return 0
  fi
  echo "+ download $url -> $MODELS_VOLUME/$dest"
  # -u 0:0: a freshly-created named volume's root dir is root-owned (the
  # fetch_gguf rationale); the read-only presence probe stays non-root. The
  # download runs in the host netns with name-only proxy-env forwarding: the
  # preflight proves the host path, so the fetch uses that same path.
  if ! docker run --rm -u 0:0 --network host -e HTTP_PROXY -e HTTPS_PROXY -e ALL_PROXY -e NO_PROXY -v "$MODELS_VOLUME:/models" "$CURL_IMAGE" -fL --retry 3 --create-dirs -o "/models/$dest" "$url"; then
    echo "FAIL: download of $url failed over the host's own network path (the path the preflight checked)." >&2
    echo "      Check host connectivity: VPN, proxy, or firewall. If you use a proxy, export" >&2
    echo "      HTTP_PROXY HTTPS_PROXY ALL_PROXY NO_PROXY (the download uses them) and re-run." >&2
    exit 1
  fi
}

# Generate the per-model API-format workflow template the Provider's
# client builds graphs from; shapes mirror the spike's measured graphs
# (P28) — the contract ComfyUIClient enforces at Provider boot (P15):

# unique INFOCHAT_PROMPT_PLACEHOLDER text field, static baked negative
# (never a second placeholder), one numeric-seed KSampler baking the
# per-model steps, decode + lanczos fit with numeric dimensions.
write_template() {
  local m="$1"
  # A compose up with the template absent auto-creates a DIRECTORY at the
  # mount source; clear it so the file can take its place (the enable-after-
  # not-enabled re-run path, P32 write side).
  if [[ -d "$WORKFLOW_FILE" ]]; then
    rmdir "$WORKFLOW_FILE"
  fi
  local vae_file="${MODEL_VAE_FILE[$m]}" decode_node fit_mp
  fit_mp="${MODEL_FIT_MP[$m]}"
  if [[ "$m" == krea_* ]]; then
    vae_file="$krea_vae"
    decode_node="VAEUtils_VAEDecodeTiled"
    # A 1x decoder decodes 896x672; the exact fit must match the decoded
    # size, never upscale it (only the 2x VAE decodes to 1792x1344).
    [[ "$krea_vae" != "$KREA_2X_VAE_FILE" ]] && fit_mp=0.57421875
  else
    decode_node="VAEDecode"
  fi
  {
    echo '{'
    echo '  "1": {'
    echo '    "class_type": "UNETLoader",'
    echo "    \"inputs\": { \"unet_name\": \"${MODEL_CKPT_FILE[$m]}\", \"weight_dtype\": \"default\" }"
    echo '  },'
    echo '  "2": {'
    echo '    "class_type": "CLIPLoader",'
    echo "    \"inputs\": { \"clip_name\": \"${MODEL_ENCODER_FILE[$m]}\", \"type\": \"${MODEL_CLIP_TYPE[$m]}\" }"
    echo '  },'
    echo '  "3": {'
    echo '    "class_type": "VAELoader",'
    echo "    \"inputs\": { \"vae_name\": \"${vae_file}\" }"
    echo '  },'
    echo '  "4": {'
    echo '    "class_type": "CLIPTextEncode",'
    echo '    "inputs": { "text": "INFOCHAT_PROMPT_PLACEHOLDER", "clip": ["2", 0] }'
    echo '  },'
    if [[ "$m" == mage_* ]]; then
      echo '  "5": {'
      echo '    "class_type": "CLIPTextEncode",'
      echo '    "inputs": { "text": "blurry, low quality, watermark, text", "clip": ["2", 0] }'
      echo '  },'
    else
      echo '  "5": {'
      echo '    "class_type": "ConditioningZeroOut",'
      echo '    "inputs": { "conditioning": ["4", 0] }'
      echo '  },'
    fi
    echo '  "6": {'
    echo "    \"class_type\": \"${MODEL_LATENT[$m]}\","
    echo "    \"inputs\": { ${MODEL_LATENT_DIMS[$m]}, \"batch_size\": 1 }"
    echo '  },'
    echo '  "7": {'
    echo '    "class_type": "KSampler",'
    echo '    "inputs": {'
    echo '      "model": ["1", 0],'
    echo '      "seed": 0,'
    echo "      \"steps\": ${MODEL_STEPS[$m]},"
    echo '      "cfg": 1.0,'
    echo '      "sampler_name": "euler",'
    echo '      "scheduler": "simple",'
    echo '      "positive": ["4", 0],'
    echo '      "negative": ["5", 0],'
    echo '      "latent_image": ["6", 0],'
    echo '      "denoise": 1.0'
    echo '    }'
    echo '  },'
    echo '  "8": {'
    echo "    \"class_type\": \"${decode_node}\","
    if [[ "$m" == krea_* ]]; then
      echo '    "inputs": { "samples": ["7", 0], "vae": ["3", 0], "upscale": -1, "tile": false, "tile_size": 512, "overlap": 64, "temporal_size": 4096, "temporal_overlap": 64 }'
    else
      echo '    "inputs": { "samples": ["7", 0], "vae": ["3", 0] }'
    fi
    echo '  },'
    echo '  "9": {'
    echo '    "class_type": "ImageScaleToTotalPixels",'
    echo "    \"inputs\": { \"image\": [\"8\", 0], \"upscale_method\": \"lanczos\", \"megapixels\": ${fit_mp}, \"resolution_steps\": 1 }"
    echo '  },'
    echo '  "10": {'
    echo '    "class_type": "SaveImage",'
    echo '    "inputs": { "images": ["9", 0], "filename_prefix": "infochat" }'
    echo '  }'
    echo '}'
  } > "$WORKFLOW_FILE"
}

wait_for_comfyui() {
  echo "+ wait for ComfyUI /system_stats (up to ${WAIT_TIMEOUT}s)"
  local deadline=$(( SECONDS + WAIT_TIMEOUT ))
  until compose_comfyui exec -T comfyui curl -fsS http://127.0.0.1:8188/system_stats >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "FAIL: ComfyUI not healthy after ${WAIT_TIMEOUT}s (check 'docker compose logs comfyui')." >&2
      exit 1
    fi
    sleep 3
  done
}

# The ETA constant is a live probe of the FINAL template just written —
# never a table lookup (P26/P29): warm-up + five timed generations, unique
# seed per run, warm-up discarded. Prints the steady-state mean (seconds).
probe_eta_seconds() {
  echo "+ ETA probe: warm-up + 5 timed runs of the written template (unique seeds)" >&2
  local probe_prompt="a plain white coffee mug on a wooden table, soft daylight"
  local graph_json
  graph_json="$(sed "s/INFOCHAT_PROMPT_PLACEHOLDER/$probe_prompt/" "$WORKFLOW_FILE")"
  INFOCHAT_PROBE_GRAPH="$graph_json" compose_comfyui exec -T \
    -e INFOCHAT_PROBE_GRAPH comfyui python3 - <<'PYEOF'
import json, os, random, time, urllib.request
graph = json.loads(os.environ["INFOCHAT_PROBE_GRAPH"])
BASE = "http://127.0.0.1:8188"
def post(path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(BASE + path, data=data, method="POST" if data else "GET")
    if data:
        req.add_header("Content-Type", "application/json")
    with urllib.request.urlopen(req, timeout=900) as r:
        raw = r.read()
    return json.loads(raw) if raw else {}
def run(seed):
    g = json.loads(json.dumps(graph))
    for v in g.values():
        ins = v.get("inputs", {})
        if isinstance(ins.get("seed"), int):
            ins["seed"] = seed
    t0 = time.monotonic()
    pid = post("/prompt", {"prompt": g})["prompt_id"]
    while True:
        time.sleep(0.5)
        h = post("/history/" + pid)
        if pid in h:
            s = h[pid].get("status", {}).get("status_str")
            if s == "success":
                break
            if s == "error":
                raise SystemExit("FAIL: ETA probe graph errored in the container")
        if time.monotonic() - t0 > 900:
            raise SystemExit("FAIL: ETA probe run timed out")
    dt = time.monotonic() - t0
    post("/history", {"delete": [pid]})
    return dt
run(random.randint(1, 2**62))
ts = sorted(run(random.randint(1, 2**62)) for _ in range(5))
print(f"{sum(ts) / len(ts):.2f}")
PYEOF
}

case "$mode" in
  local)
    choose_model
    if [[ "$model" == krea_* ]]; then
      choose_krea_decode
      print_krea_asset_licences
    fi
    choose_translate_prompt

    # Hardware gate for the LOCAL path: an AMD ROCm GPU is required. This is
    # the ROCm-only scope the picker states — fail before any download on a
    # host without the device nodes.
    if [[ ! -e /dev/kfd || ! -e /dev/dri ]]; then
      echo "FAIL: local install needs an AMD ROCm GPU (/dev/kfd + /dev/dri not found)." >&2
      echo "      The local path is ROCm-only, validated on Strix Halo (gfx1151) alone." >&2
      exit 1
    fi

    # Preflight BEFORE any multi-GB download (analysis P22): HEAD-check every
    # asset URL of the chosen tier — for Krea INCLUDING the two community
    # VAE files — then verify disk and memory/VRAM for it.
    echo
    echo "Preflight checks for ${model}:"
    head_check "${MODEL_URL_CKPT[$model]}"
    head_check "${MODEL_URL_ENCODER[$model]}"
    head_check "${MODEL_URL_VAE[$model]}"
    if [[ "$model" == krea_* ]]; then
      head_check "$KREA_REALVAE_URL"
      head_check "$KREA_2X_VAE_URL"
    fi
    require_disk_gb "${MODEL_DISK_REQ_GB[$model]}"
    require_mem_gb "${MODEL_DISK_REQ_GB[$model]}"

    if [[ -z "$MODELS_HOST_DIR" ]]; then
      docker volume create "$MODELS_VOLUME" >/dev/null
    fi
    fetch_asset "${MODEL_URL_CKPT[$model]}"    "diffusion_models/${MODEL_CKPT_FILE[$model]}"
    fetch_asset "${MODEL_URL_ENCODER[$model]}" "text_encoders/${MODEL_ENCODER_FILE[$model]}"
    fetch_asset "${MODEL_URL_VAE[$model]}"     "vae/${MODEL_VAE_FILE[$model]}"
    if [[ "$model" == krea_* ]]; then
      fetch_asset "$KREA_REALVAE_URL" "vae/${KREA_REALVAE_FILE}"
      fetch_asset "$KREA_2X_VAE_URL"  "vae/${KREA_2X_VAE_FILE}"
    fi

    write_template "$model"

    # (Re)create the overlay container so a model switch restarts ComfyUI
    # clean against the populated models dir (design addendum: model switch
    # recreates the container).
    echo "+ docker compose -f docker-compose.yml -f docker-compose.comfyui.yml up -d --force-recreate comfyui"
    compose_comfyui up -d --force-recreate comfyui
    wait_for_comfyui

    # Healthcheck via /system_stats returns JSON (acceptance item 6). The
    # overlay publishes no host port, so the check runs inside the container.
    echo "+ healthcheck (/system_stats):"
    compose_comfyui exec -T comfyui curl -fsS http://127.0.0.1:8188/system_stats
    echo

    # On a model switch, offer to delete the PREVIOUS model's files (P24).
    if [[ -n "${existing_model:-}" && "${existing_model}" != "$model" \
          && -n "${MODEL_LABEL[$existing_model]:-}" ]]; then
      offer_delete_previous_files "$existing_model" "$model"
    fi

    eta_seconds="$(probe_eta_seconds)"
    if ! [[ "$eta_seconds" =~ ^[0-9]+\.[0-9]{2}$ ]]; then
      echo "FAIL: ETA probe returned no steady-state mean ('$eta_seconds')." >&2
      exit 1
    fi

    set_prop infochat.image.base-url "$BASE_URL_LOCAL"
    set_prop infochat.image.workflow-file "$WORKFLOW_FILE_IN_CONTAINER"
    set_prop infochat.image.steady-state-seconds "$eta_seconds"
    set_prop infochat.image.model "$model"
    set_prop infochat.image.translate-prompt "$translate_prompt"
    echo "local /image backend ready: model $model via $BASE_URL_LOCAL"
    echo "ETA constant (probe mean of the written template): ${eta_seconds} s"
    ;;

  remote)
    choose_model
    if [[ "$model" == krea_* ]]; then
      choose_krea_decode
    fi
    choose_translate_prompt
    echo
    # D77 two-box firewall disclosure, printed BEFORE the operator commits to
    # the URL (the 4-llm.sh disclosure-before-commit shape).

    # The wizard documents the requirement; it cannot verify network topology
    # (design addendum), so the printed text IS the control.
    echo "FIREWALL REQUIREMENT (D77 two-box): ComfyUI has NO authentication and its"
    echo "API executes submitted workflow graphs — the endpoint is code execution on"
    echo "the hosting box. You MUST firewall the backend port so that ONLY the single"
    echo "Provider host can reach it; 'private LAN' alone is not a control. The remote"
    echo "box must be operator-owned infrastructure (never a third-party service), and"
    echo "prompts cross the LAN in cleartext HTTP."
    echo
    if [[ "$defaults" -eq 1 ]]; then
      echo "FAIL: --defaults cannot configure the remote backend; run interactively." >&2
      exit 1
    fi
    read -rp "Remote ComfyUI base-url (e.g. http://192.168.1.20:8188): " remote_url
    if [[ -z "$remote_url" ]]; then
      echo "FAIL: a base-url is required for the remote backend." >&2
      exit 1
    fi
    case "$remote_url" in
      http://*|https://*) ;;
      *) echo "FAIL: base-url must start with http:// or https:// (got '$remote_url')." >&2; exit 1 ;;
    esac

    # A local->remote switch moves the backend off this box: stop the
    # overlay container, and offer to reclaim the previous install's model
    # files when that install was local (a remote install shares no blobs).
    stop_comfyui_if_present
    if [[ -n "${existing_model:-}" && "${existing_url:-}" == "$BASE_URL_LOCAL" \
          && -n "${MODEL_LABEL[$existing_model]:-}" ]]; then
      offer_delete_previous_files "$existing_model" "none"
    fi

    # The remote path runs NO probe against the entered URL, so it writes
    # NO ETA constant — a number from different hardware would be a lie
    # (P30). clear_image_props also drops a previous local install's.
    clear_image_props
    write_template "$model"
    set_prop infochat.image.base-url "$remote_url"
    set_prop infochat.image.workflow-file "$WORKFLOW_FILE_IN_CONTAINER"
    set_prop infochat.image.model "$model"
    set_prop infochat.image.translate-prompt "$translate_prompt"
    echo "remote /image backend ready: model $model via $remote_url"
    ;;

  none)
    record_not_enabled
    stop_comfyui_if_present
    echo "/image not enabled: infochat.image.base-url left unset, so the command"
    echo "does not exist (D73). Re-run this step to enable it later."
    ;;
esac

# Config changes reach the Provider at (re)start — on a re-run against a
# running deployment, point at the documented lifecycle wrapper (4-llm.sh
# leaves the restart to the operator the same way).
if [[ "$mode" != "none" ]] && docker compose -f "$COMPOSE_FILE" --profile prod ps -q infochat-provider 2>/dev/null | grep -q .; then
  echo
  echo "NOTE: the Provider is running — run 'prod/scripts/apps.sh restart' so it"
  echo "picks up the new infochat.image.* config."
fi
