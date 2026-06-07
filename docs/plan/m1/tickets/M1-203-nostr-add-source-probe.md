---
id: M1-203
title: "Nostr /add-source: StreamSource-shaped relay probe instead of guaranteed SSRF rejection"
status: pending
created: 2026-06-07
last_updated: 2026-06-07
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/UrlProbe.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/source/KindResolver.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command
  - infochat-provider/src/test/java/app/zcat/infochat/provider/source
complexity: medium
risk: medium
round_cap: 2
security_relevant: true
migration_touch: false
out_of_scope:
  - the SSRF pin-map internals (PinnedDnsResolver static lock) — M1-191's; this ticket consumes the guard's existing WebSocket surface, it does not modify guard internals
  - bootstrap-loader probe bypass — already correct per spec ("the operator is trusted"), preserved untouched
  - Nostr stream ingestion, relay supervision, and NostrStreamSource — collector-side, unchanged (M1-202 touches the Registrar)
  - HTTP-shaped kind probing (RSS/Bluesky/Reddit HEAD-or-range-GET path) — unchanged
  - the /add-source --tags requirement and tag-conflict rules — unchanged
acceptance:
  - "Per docs/spec/commands.md §Source management — \"For StreamSource-shaped kinds (Nostr in v1) the equivalent check is a single connection attempt against the first relay in the supplied `config`; failure produces the same friendly error.\" — /add-source with a wss:// relay URL performs a connection-attempt probe instead of the HTTP probe: a named test against a local fake relay asserts a reachable, policy-allowed relay yields a created source row (today KindResolver maps wss/ws → NOSTR but the handler unconditionally calls urlProbe.probe(), whose HTTP scheme allowlist rejects wss → SCHEME_NOT_ALLOWED → every Nostr /add-source fails with a misleading SSRF-blocked reply)"
  - "Per docs/spec/commands.md §Source management — \"The probe runs under the same allowlist, redirect cap, and timeout caps as fetcher traffic\" — the relay connection attempt goes through the shared SSRF guard: a named test asserts a relay resolving to a blocked address range is rejected and no source row is written"
  - "Per docs/spec/commands.md §Source management — \"a 4xx/5xx response, an SSRF rejection, or a timeout produces a friendly error and **no row is written**\" — a named test asserts an unreachable relay produces the friendly error and no source row"
  - "A policy-allowed, reachable wss relay no longer yields the SSRF-blocked reply text: a named test pins the reply distinction between genuine SSRF rejection and ordinary unreachability"
  - "Existing HTTP-shaped /add-source probe tests stay green"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/command
    - infochat-provider/src/test/java/app/zcat/infochat/provider/source
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/commands.md §Source management
  - docs/spec/security.md §SSRF
decision_refs:
  - D38
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-203: Nostr /add-source: StreamSource-shaped relay probe instead of guaranteed SSRF rejection

## Context

Unified finding P15 (`deep-code-review/v2/UNIFIED.md` §2, med):
AddSourceCommandHandler resolves the kind (wss/ws → NOSTR via
KindResolver:112) and then unconditionally probes via the HTTP prober
(:164-166); the SSRF guard's HTTP scheme allowlist rejects wss with
SCHEME_NOT_ALLOWED, so **every** Nostr /add-source fails — and fails
with a reply blaming SSRF policy for a URL the policy was never meant
to reject this way.

Re-anchoring (draft time) upgraded this from "skip the probe or add a
WS probe — pick one": the spec already mandates the WS-shaped check
verbatim ("a single connection attempt against the first relay in the
supplied `config`"), so the probe-skipping alternative is off the
table. The SSRF guard already exposes a WebSocket dial surface
(checkAndPinForWebSocket — used by collector-side Nostr ingestion), so
the provider-side probe consumes that, not a new guard path.

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T26 under `deep-code-review/v2/`
  (kimi-folder prov F6).
- M1-191 rewrites the per-host pin internals under this surface —
  different layer; no ordering dependency, but do not run the two
  concurrently if the probe leg ends up touching guard call sites.
- The "first relay in the supplied config" wording implies the probe
  input is the relay list/filter spec (identifier per D38), not just a
  bare URL — align the probe argument with what /add-source actually
  stores for stream sources.
