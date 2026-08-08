#!/bin/bash

# Aged-file janitor for the D75 backend no-retention end state: removes
# output and temp files older than INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES
# (default 15), then execs the ComfyUI launch line passed as arguments.
set -eu

ttl="${INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES:-15}"
out=/opt/ComfyUI/output
tmp=/opt/ComfyUI/temp
mkdir -p "$out" "$tmp"

(
    while :; do
        sleep 60
        find "$out" "$tmp" -type f -mmin +"$ttl" -delete
    done
) &

exec "$@"
