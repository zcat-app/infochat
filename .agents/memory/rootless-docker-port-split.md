---
name: rootless-docker-port-split
description: Random "address already in use" IT failures mean the host ephemeral port range overlaps rootless docker's fixed ~40000-60999 publish band — fix is the HOST sysctl at 32768-39999 (the daemon-netns sysctl is a proven no-op; trust only the docker run -P probe).
metadata:
  type: project
---

Rootless docker publishes container ports from an internal allocator whose
band is effectively fixed at the 40000-60999 half of the default
32768-60999 ephemeral range. When the HOST kernel hands outbound sockets
(test JVMs, prod stacks' connections, agent sessions, TIME-WAIT residue)
from the same band, container publishes race those sockets and random
integration tests die at container startup with:

    RootlessKit PortManager.AddPort(): listen tcp4 0.0.0.0:<port>:
    bind: address already in use

**Environment failure, not a regression** (engineering-rules §5's named
environment class). Signature: different victim test each run, zero failures
in the modules the diff touches, failure before any test logic runs
(`ContainerLaunchException` / `Container startup failed for image
pgvector/pgvector:pg16`). 2026-08-15: four same-day suite failures before
diagnosis; every "green in a quiet window" run was a lucky draw.

**Fix (the only live lever):** move the HOST kernel band off 40000:

```bash
sudo sysctl -w net.ipv4.ip_local_port_range="32768 39999"
# durable: align /etc/sysctl.d/99-docker-port-split.conf to the SAME
# direction (32768-39999) — its original 40000-60999 direction predates
# the rootless-docker finding and re-arms the race at reboot.
```

**Dead ends — do not repeat (all burned 2026-08-15):**

- A sysctl into the daemon's network namespace is a NO-OP for the publish
  band: `nsenter -t <pid> -n sysctl -w …` reports success, yet docker keeps
  drawing 40000-band ports. The band does not follow the daemon netns
  sysctl, live or across restarts.
- Namespace forensics mislead: under `--detach-netns` the rootlesskit parent
  stays in the HOST netns and the child holds the daemon netns — and
  `/proc/<pid>/root/proc/sys/net/...` resolves against the READER's netns,
  so it silently reads the host no matter which pid you name. Do not
  diagnose through procfs-into-netns.
- `verify-serialized.sh`'s original premise (header + Jul 30 split file)
  was that the daemon honors its netns port range and only the split
  direction mattered; on rootless docker that premise is false.
- "Drain-holding" a port band with a userspace squatter (burned
  2026-08-16, `/tmp/opencode/drain-ports.py`): an agent bound+listened
  ALL 7,232 ports of the host's 32768-39999 band to "steer rootlesskit's
  draws up" — but rootlesskit already draws 40000+ and never touches the
  low band. What DOES live in the low band is the host kernel's ephemeral
  allocation for `bind(0)` — test mock servers (`SseMockServer`),
  Testcontainers clients, everything. Result: total ephemeral exhaustion;
  every port-0 bind in the verify JVMs died `BindException: Address
  already in use` (10 tests, infochat-llm-adapter, failure BEFORE any
  test logic — same environment-failure class as the Ryuk collision,
  different mechanism). Diagnosis: `ss -tlnp` shows thousands of LISTEN
  sockets held by one pid. Fix: kill the squatter; never "hold" ports.

**The only trustworthy probe — ask docker itself** (the allocator is what
matters, not any sysctl's opinion):

```bash
id=$(docker run --rm -d -P pgvector/pgvector:pg16)
docker port "$id" | head -1     # a 40000-band draw = publish band unchanged
docker rm -f "$id" >/dev/null
```

Healthy state: host `32768-39999`, docker draws 40000-band. Verify-start
guard: `scripts/verify-serialized.sh` warns when the host band overlaps
40000-60999 and prints the fix. Related: [[clean-verify-monitoring]].
