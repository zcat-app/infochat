# Future — Public IPFS publishing

> **Status: DECLINED (2026-08-08) — this idea does not fit the project.**
> It was considered during the v2 design conversation and declined as out of
> scope: a publishing/demo surface, not a messaging or aggregator feature —
> it would add a third service, an operator-run IPFS node, and
> immutable-content liability, against zero user demand. The decline record
> is [docs/plan/future-features.md](../../plan/future-features.md) §J1. Do
> NOT implement against this file; it is kept only as a record of what was
> considered.

## What the idea was (summary)

Publish a static, JS-free HTML page summarizing recent activity to IPFS,
updating an IPNS name on the existing 12h summary cadence, so a stable URL
always serves the latest snapshot — an uncensorable demo of what infochat
does for prospective users. The v2 design sketched: a `public` pseudo-user
scope (unreachable through the messaging adapter) owning an admin-managed
RSS-only source allowlist; a separate read-only submodule rendering a strict
include-list of fields (post titles, source names, bare source URLs,
published_at, tier-1 tags — no post UIDs, no user-derived data, no external
resources); an operator-run Kubo node plus one pinning service; the IPNS key
treated as an operator secret; per-deployment opt-in with an on-page
disclaimer (12h-old snapshot, third-party content, best-effort removal).

## Why it was declined

The feature changes the project's threat model (private chat → global
permanent publication) and legal posture (copyright, GDPR Article 17
incompatibility with content-addressed storage) for no user demand, and adds
a third service plus an IPFS node the operator must run. Declined by user
decision 2026-08-08; only a new user request reopens it. The full design
detail (settled decisions 1–7, explicit out-of-scope list, deferral
reasoning) is preserved in git history at commit `d9b940b3`.
