# v5 ticket cut (M1-284..M1-312) — parallelization map

**Date:** 2026-06-11. Companion to `v5-deep-review-backlog.md`; lane
semantics per `docs/plan/audit/parallelization.md` (lanes run concurrently
in independent worktrees; tickets within a wave are pairwise file-disjoint;
ordering between waves is rebase-cost avoidance, not `blocked_by` — no v5
ticket has a hard start dependency, so frontmatter stays as cut).

## Operating constraints (recorded project rules)

- **Concurrency width 3–5 worktrees.** Full `mvn verify` runs serialize
  (fixed test port 8081 — check `pgrep -af "clean verify"` first); targeted
  builds use `-pl <module> -am` (shared ~/.m2 SNAPSHOT hazard).
- **MIG lane:** only M1-290 carries `migration_touch` — no migration
  contention in this cut; still re-verify V50 is free at its start.
- **Merges serialize on the shared main checkout**; audit each squash with
  `git show --stat`; check fork-distance before merging long-lived branches
  (M1-284 especially).
- **Collect the user decisions at Wave-1 kickoff** so no wave stalls
  mid-flight: M1-284 (implement vs defer — default implement), M1-288
  (implement group /summary), M1-290 (U-56 column drop yes/no), M1-304
  (dependency approval), M1-301 (both forks), M1-302 (fail-open default),
  M1-305 (schedule-or-amend), M1-311 (strip vs carve-out); minor in-ticket
  forks with recorded defaults: M1-293 (U-20/U-31), M1-294 (U-30),
  M1-300 (fail-fast bindings).

## Waves

| Wave | Tickets | Lanes / why disjoint |
|---|---|---|
| **1** | **M1-284** (XL — spans waves 1–2), M1-286, M1-289, M1-290, M1-296, M1-304 | 284 provider messaging/digest/group + design 06; 286 collector stream/reeval; 289 provider asset corner; 290 MIG+core tests; 296 llm-adapter; 304 messaging pom only. All six pairwise file-disjoint (284 vs 304 share the module, not files). |
| **2** | M1-285, M1-287, M1-288, M1-295, M1-299 | 285 adapters (after 284 lands — CT1 ordering; starts anyway if 284 drags, only MessagingException.java is shared and 284 owns it); 287 nostr (after 286 — NostrStreamSource); 288 provider Summary (head of the provider hot-file chain); 295 collector eval/fetch + collector properties; 299 core smalls. |
| **3** | M1-291, M1-293, M1-297, M1-300, M1-310 | **Gate: M1-284 merged** (297 shares StageProgressNotifier/provider-messaging with it). 291 ssrf + NostrRelayConnection (after 287) + deployment.md (alone this wave); 293 signal (after 285 — SignalJsonRpcClient); 297 provider chat + /stop; 300 asset config grammar (after 295 — collector properties); 310 fetcher quartet (disjoint from 295's reddit). |
| **4** | M1-292, M1-294, M1-301, M1-303 | 292 log hygiene (after 291 ssrf, 296 LlmHttpSupport, 300 AssetSnapshotFetcher); 294 simplex + Signal codec/adapter touch (after 285/293, design 06 free); 301 trust-anchor docs + AdapterRegistry (deployment.md free again; **if user picked implement-derivation, move 301 after 294** — adapter files); 303 localization (after 288; next link of the provider chain). |
| **5** | M1-298, M1-302, M1-305, M1-308 | 298 rename fan-out (collector+provider call-site sweep — needs this quiet window: fetchers/stream landed, provider chain paused); 302 ops posture (collector properties + provider/health + deployment docs, all free); 305 observability docs (design 06 editors 284/293/294 all landed); 308 messaging/collector lows (after 285/293/294; disjoint from 298's rename surface). |
| **6** | M1-306 | Provider mediums — needs 297 (StageProgressNotifier), 303 (Retry), 284 (InboundRouter), 298 (audit-adjacent files) all landed. |
| **7** | M1-307 | Provider lows sweep — by design last of the wide provider edits (overlaps 303/306/288 files). |
| **tail** | M1-309 → M1-311 → M1-312 | Serial: 309 shares Invite/Ban with 307 and audit call sites with 298; 311 (provenance sweep) must follow ALL code tickets and conflicts with everything; 312 (doc truth) last — re-grep its anchors, several are conditional on earlier tickets. M1-312 may instead pair with M1-309 (disjoint files) if the user wants 311 strictly final. |

## Provider hot-file chain (the serializing spine)

`SummaryCommandHandler` / `RetryCommandHandler` / `InboundRouter` /
`StageProgressNotifier` / `BundleKeys` force this order regardless of wave
capacity: **288 → 297 → 303 → 306 → 307 → 309** (284 before 297; 298
between 303 and 306). Everything else is filler that can shift waves
freely as long as it stays out of these files.

## Priority note

The six HIGHs (U-01..U-06 → M1-284..M1-289) all sit in waves 1–2 by
construction; if capacity is short, drop wave width elsewhere, not there.
