#!/bin/bash

# Aged-file janitor for the D75 backend no-retention end state: removes
# output files older than INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES (default 15),
# then execs the ComfyUI launch line passed as arguments.
set -eu

ttl="${INFOCHAT_COMFYUI_OUTPUT_TTL_MINUTES:-15}"
out=/opt/ComfyUI/output
mkdir -p "$out"

(
    while :; do
        sleep 60
        find "$out" -type f -mmin +"$ttl" -delete
    done
) &

exec "$@"
