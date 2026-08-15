---
name: measurements-never-ride-prod-containers
description: Measurement/benchmark runs target isolated or test instances, never the prod containers; nobody stops a prod service without logging it where other sessions read.
metadata:
  type: feedback
---

Measurement runs (prompt quality, latency probes, translation campaigns,
model bake-offs, image-prompt benchmarks) ride **isolated harness containers
or the test instance** — never the prod containers as a backend, and never by
switching prod's runtime config to the model under test. Prod serves users;
a measurement behind prod's endpoints both contaminates the measurement
(production traffic interleaves) and puts measurement load on the serving
path.

**No prod service is stopped, paused, or reconfigured without logging it**
— which service, why, expected duration — in a place other sessions read
(the local memory store at minimum; a handoff note if a session owns it).
An unlogged stop surfaces hours later as a mystery ("why is X down? /
why is X up only 6h?") that costs another session a forensic session to
explain; docker's event ring rotates and `RestartCount=0` proves deliberateness
but not actor or motive.

Motivating case (2026-08-15): an image-translation measurement drove prod's
ComfyUI at generation cadence overnight, then prod ComfyUI sat stopped ~9.5h
after an unlogged stop — another session spent a forensic pass reconstructing
this from container logs. The isolated alternative existed the whole time
(an exited test-instance ComfyUI). Sequencing note: heavy GPU measurements
also contend with the M1-826-class live probes and verify batches on a
single-GPU box — schedule, don't collide.
