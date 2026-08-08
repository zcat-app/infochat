# Purpose-built ROCm ComfyUI image for the /image one-box backend (D77);
# brought up only via docker-compose.comfyui.yml. Torch stack mirrors the
# measured host baseline (torch 2.12.0a0+rocm7.13, AMD gfx1151 nightly).

# The CMD flags + env below are measured requirements, not tunables —
# rationale and numbers: docs/design/future/image-generation.md addendum.
# Do not drop one without re-measuring.

# Output dir lives on tmpfs (overlay) and janitor.sh sweeps aged files —
# the image half of the D75 no-retention end state; ComfyUI has no delete
# API for output files (Provider-side half is M1-802's history clear).

FROM ubuntu:26.04

ARG COMFYUI_COMMIT=6f7cd7fceaaf60d2669b554936394a7412c6fde5

RUN apt-get update && apt-get install -y --no-install-recommends \
        python3 \
        python3-venv \
        python3-pip \
        curl \
        ca-certificates \
        libatomic1 \
        libgomp1 \
    && rm -rf /var/lib/apt/lists/*

RUN python3 -m venv /opt/venv
ENV PATH=/opt/venv/bin:$PATH

# Pinned pre-release stack from the AMD gfx1151 nightly index — the exact
# build measured on the host; rocm[libraries] and triton arrive as pinned
# dependencies of torch from the same index.
RUN pip install --no-cache-dir --pre \
        torch==2.12.0a0+rocm7.13.0a20260411 \
        torchvision==0.27.0a0+rocm7.13.0a20260411 \
        torchaudio==2.11.0+rocm7.13.0a20260426 \
        --index-url https://rocm.nightlies.amd.com/v2/gfx1151/

RUN curl -fsSL "https://github.com/comfyanonymous/ComfyUI/archive/${COMFYUI_COMMIT}.tar.gz" \
        | tar -xz -C /opt \
    && mv "/opt/ComfyUI-${COMFYUI_COMMIT}" /opt/ComfyUI

# Bare torch/torchvision/torchaudio entries in requirements.txt are already
# satisfied by the pinned ROCm builds above; pip leaves them untouched.
RUN pip install --no-cache-dir -r /opt/ComfyUI/requirements.txt

COPY janitor.sh /opt/janitor.sh
RUN chmod +x /opt/janitor.sh

ENV TORCH_ROCM_AOTRITON_ENABLE_EXPERIMENTAL=1

WORKDIR /opt/ComfyUI
EXPOSE 8188
HEALTHCHECK --interval=10s --timeout=5s --retries=30 --start-period=120s \
    CMD curl -fsS http://127.0.0.1:8188/system_stats || exit 1

ENTRYPOINT ["/opt/janitor.sh"]
CMD ["python3", "main.py", "--listen", "--port", "8188", \
     "--disable-mmap", \
     "--bf16-vae", \
     "--highvram"]
