---
name: v1-0-0-tag-pulled-pre-announcement
description: v1.0.0 was tagged at 3fb97365 then DELETED (local + origin) on 2026-07-25, before any announcement, because live testing found three user-facing defects that must ship first. There is currently NO release tag on this repo — do not assume one exists.
metadata:
  node_type: memory
  type: project
---

**2026-07-25: the `v1.0.0` tag was created and then pulled.** It pointed at
commit `3fb97365` (tag object `461b8858`). Deleted from the local repo and from
`origin` (`zcat-app/infochat`) at the operator's request after live SimpleX
testing surfaced three defects they wanted fixed before announcing:
`/summary` flooding a DM (M1-687), the scheduled group digest reporting
"no posts" over a full corpus (M1-688/M1-689), and the chat agent declining
off-feed questions (M1-690).

Deletion was safe to do: **no GitHub Release was attached** (the public API
returned an empty release list and 404 for the tag), so nothing but the bare
ref existed, and the tag was never announced. The annotated tag object's full
content was captured before deletion so the tag can be recreated verbatim — it
records the release state (3065 tests green, the live-test tally, the
adversarial-campaign results). If you need it back and the scratchpad copy is
gone, reconstruct from commit `3fb97365` and the `M1-686` era ticket record.

**Consequence for anyone reasoning about release readiness:** the absence of a
tag is now the expected state, not a sign something was lost. The authoritative
release-state record remains `docs/plan/v1-verification-truth.md` — see
[[release-state-source-ranking]] for how to rank sources. Re-tagging is owed
once M1-687..690 land.

**UPDATE 2026-08-29 (owner): the project has moved on to v2** — this note is
history, not current state. Do not reason or speak in "v1 posture" terms; check
for v2 planning docs before citing v1-era release state.
