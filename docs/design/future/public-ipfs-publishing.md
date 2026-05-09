# Future — Public IPFS publishing

> **Status: v2 design notes. NOT part of v1. Do NOT implement against this
> without first promoting it to spec.** This file captures decisions made
> during a design conversation so the reasoning isn't lost; it is not a
> commitment, not part of `spec/decisions.md`, and not part of any MVP.

## Goal

Publish a static, JS-free HTML page summarizing recent activity to IPFS,
update an IPNS name on the existing 12h summary cadence, so a stable URL
always serves the latest snapshot. Purpose: an uncensorable demo of what
infochat does for prospective users.

## Decisions settled (v2 candidate)

1. **Publishable scope is a `public` pseudo-user**, not a synthetic new
   scope concept. The user has its own admin-managed source list (via
   the existing `/add-source` flow, run as `public` by a bot admin).
   The `public` user is **unreachable through the messaging adapter** —
   no SimpleX contact ID maps to it; it cannot send or receive
   messages. It exists only as a row that owns sources/preferences.
2. **Source allowlist is RSS-only in v1 of the feature.** Social-media
   sources (Bluesky, Nostr, Nitter, etc.) are excluded — they have no
   real title field, carry higher copyright/PII/quarantine risk, and
   are exactly the content category least suited to an immutable
   medium. May be revisited later, metadata-only.
3. **Page content is the summary prose plus a strict include-list of
   structured fields**: post.title, source name, source URL (bare),
   published_at, tier-1 tags. No post UIDs (read-only, no
   interaction = UIDs are pure fingerprinting). No user-derived data
   of any kind. No external resources (images, fonts, analytics).
4. **Separate submodule.** A third Quarkus module alongside Collector
   and Provider, with its own process and deployment. Reads the
   shared Postgres schema directly (read-only, ideally through a
   dedicated SQL view that exposes only publishable fields). Does
   NOT call Collector over HTTP — Collector remains headless per
   CLAUDE.md. Subscribes to the existing LISTEN/NOTIFY channel for
   change events; renders on the 12h cron.
5. **IPFS hosting:** operator runs their own Kubo node; one pinning
   service (Pinata or equivalent free tier — data is small) as a
   second pin for resilience. IPNS private key is an operator
   secret, treated like an admin credential, with a documented
   rotation procedure.
6. **Per-deployment opt-in**, disabled by default. Operator must
   consciously enable and accept the legal posture (copyright, GDPR
   Article 17 incompatibility with content-addressed storage,
   jurisdiction).
7. **Disclaimer rendered on the page itself**: 12h-old snapshot,
   third-party content, removal requests are best-effort because
   IPFS is content-addressed.

## Explicitly out of scope

- LLM-generated post titles (hallucination on an immutable medium).
- Republishing user content of any kind, including `/save` lists.
- Synthesized titles from social-post bodies.
- A Collector HTTP API to serve the publisher.
- Multi-operator shared IPNS name.

## Why deferred

The feature changes the project's threat model (private chat → global
permanent publication) and legal posture. Belongs after v1 has run
in anger long enough to trust Stage 1/2 ingest evaluation, quarantine
review, and the bootstrap-source flow.
