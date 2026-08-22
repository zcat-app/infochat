---
name: config-rewrite-needs-boot-verify
description: "A runtime-config rewrite on a live stack is not DONE until the affected service has been restarted and re-verified — health polls answer for the pre-rewrite process, because config is read at boot and nothing hot-reloads."
metadata:
  type: feedback
---

v2.0.0 campaign, D-20: a boot-fatal rewrite of `bootstrap-sources.json`
(JSON-lines instead of the required top-level array) went undetected for
11.5 hours. Every check in between passed because they polled the CURRENTLY
RUNNING processes — which were still serving the pre-rewrite config. The next
restart crash-looped (RestartCount 16), and the failure surfaced at the worst
possible moment. The same shape applies to any boot-time-read file:
`application.properties`, `bootstrap-*.json`, `secrets.env`.

**Why:** "the file was rewritten" and "the stack is healthy" are both true
claims about DIFFERENT states. Only a boot joins them. See
[[audit-claims-vs-sequences]] for the doc-audit twin of this failure class.

**How to apply:** after ANY rewrite of a runtime config file on a live stack,
restart the affected service and re-run `prod/scripts/8-verify.sh` before
declaring the step done. The mechanical detector (a mounted file newer than
the service's `StartedAt` warns) is filed as M1-907; the operator rule lives
in `docs/testing/USER_TEST_PLAN.md`.
