---
name: rootless-docker-port-split
description: Random "address already in use" IT failures mean the rootless docker netns port range overlaps the host's — find the daemon-netns CHILD pid (never the rootlesskit parent), fix live with one sudo nsenter, know it resets on daemon restart.
metadata:
  type: project
---

A rootless Docker daemon picks each container's published host port from the
ephemeral port range of its **own private network namespace**, which it copies
from the host **at daemon start**. When the two ranges overlap, every container
publish races live host sockets (long-running stacks' outbound connections,
test JVMs' client sockets, TIME-WAIT residue) and random integration tests die
at container startup with:

    RootlessKit PortManager.AddPort(): listen tcp4 0.0.0.0:<port>:
    bind: address already in use

**This is an environment failure, not a regression** (engineering-rules §5's
named environment cause). The signature: different victim test each run, zero
failures in the modules the diff touches, failure before any test logic runs
(`ContainerLaunchException` / `Container startup failed for image
pgvector/pgvector:pg16`). A host-level port-split sysctl does NOT survive a
docker daemon restart — the fresh netns re-copies the host range and the race
returns.

**PID trap (cost an afternoon, 2026-08-15):** under `--detach-netns` the
rootlesskit PARENT stays in the host netns; the daemon's netns belongs to its
CHILD process (a `/proc/self/exe` rootlesskit child). `nsenter -t <parent>`
sets the HOST, not the daemon — it "fixes" the symptom only by moving the host
band (an accidental reversed split), and any later host restore re-arms the
race. Discover the right pid mechanically: among `pgrep -f 'rootlesskit|dockerd'`,
take the process whose `/proc/<pid>/ns/net` inode DIFFERS from the reader's
and whose `/proc/<pid>/root/proc/sys/net/ipv4/ip_local_port_range` is readable
(reads the child netns without root).

**Diagnosis (no root needed):** compare the bands — overlap means the race is
live.

```bash
awk '{print $1"-"$2}' /proc/sys/net/ipv4/ip_local_port_range   # host
# daemon: the child pid per the discovery rule above, then
awk '{print $1"-"$2}' /proc/<child-pid>/root/proc/sys/net/ipv4/ip_local_port_range
```

**Live fix (sudo, no daemon restart, no container restart):** point the
DAEMON's namespace at a band disjoint from the host's — e.g. host stays
`40000 60999` (what the persistent `/etc/sysctl.d` split file declares),
daemon gets `32768 39999`:

```bash
sudo nsenter -t <child-pid> -n sysctl -w net.ipv4.ip_local_port_range="32768 39999"
```

**The fix resets on the next daemon restart** (netns re-copies). After any
docker daemon restart or reboot, re-run the diagnosis. Durable option: a
systemd drop-in applying the sysctl into the daemon namespace at start
(`ExecStartPost` + nsenter), or any equivalent start hook — an operator
decision, per host. `scripts/verify-serialized.sh` prints an overlap warning
naming the correct pid and fix command at verify start. Related:
[[clean-verify-monitoring]].
