# Subscription & tag model — v1 redesign (design decision)

> **Decided:** 2026-07-13 (operator + Claude, in conversation). **Scope: v1**
> (operator decision — this is v1, *not* a future-features item).
> **Status: decided, NOT yet spec'd or ticketed. PARKED behind M1-620** (in
> progress: admin `/invite bot-contact` for the SimpleX rotated-link onboarding
> gap). This note preserves the decision so the conversation isn't lost; it is
> the input to the eventual spec amendment + ticket(s).
> **Current release state authority:** `docs/plan/v1-verification-truth.md`
> (this is a v1 subscription-UX gap in that doc's terms).

## Problem

Two user-facing selection layers — **source subscription** and **`/follow-tag`** —
overlap confusingly. A non-admin new user can't browse the source catalogue,
can't subscribe by tag, and `/add-source` overloads "create a source" with
"subscribe." Even the designers got confused about what `/follow-tag` filters
(see §Two tag layers). Verdict: the subscription UX is a real v1 gap.

## Two tag layers (the exact thing that confused us — recorded so nobody re-confuses it)

- **`source.bootstrap_tags`** — fixed per source (set at add/bootstrap). Roles:
  (a) deterministic **fallback** the tagger uses when it can't tag a post;
  (b) seeds the tag vocabulary. **NOT the filter target.**
- **`post.tags`** — `TEXT[]` inline on each post (V7 chose an array, not a
  `post_tag` join). Written by the LLM tagger **from post content**; falls back
  to that post's source's `bootstrap_tags` on tagger failure.
- **`/follow-tag` filters on `post.tags`** (`EligiblePostQuery`: `p.tags && <followed>`).
  So "follow ai" = *posts the tagger judged to be about AI*, from sources in
  your world — content-based, **not** "sources labeled ai."

The original intent ("subscribe to sources carrying tag X") evolved into per-post
content filtering. That's fine for the **digest**, but confusing as a
**subscription** concept — which is what this redesign resolves.

## Converged model (decided)

1. **Bootstrap sources = implicit public corpus.** Everyone gets them by default,
   **no per-user subscription rows** — query `source_origin='bootstrap'` directly.
   New bootstrap sources are auto-included (no fan-out job).
2. **Custom (user-added) sources = private.** The adder gets a
   `source_subscription` row; only they see/use it (their digest + their RAG).
   **Never listed or exposed to any other user.**
3. **`/follow-tag` = the single user-facing knob.** Narrows the **digest** by
   post-topic; default = everything (`ALL` mode, no narrowing).
4. **Chat/RAG = always broad.** Searches the user's whole world (public bootstrap
   + own customs), **never** narrowed by `/follow-tag`. (Also resolves today's
   inconsistency: `SearchPostsTool` honours follow-tag, `SemanticSearchTool` /
   `GetReferencesTool` don't — decouple all of them from follow-tag.)
5. **`/list-sources` (non-admin) = bootstrap catalogue (browseable) + the user's
   own customs** — never others' customs.
6. **`source_subscription` holds ONLY** custom subscriptions + optional bootstrap
   **exclusions** (unfollow-a-bootstrap = a tombstone row).

## UX-guidance principle (first-class requirement, not a footnote)

*If the people building it get lost, users get more lost.* The wording **is** the
feature. Short, clear, setup-script-style in-band hints — especially the
digest-narrows-but-chat-stays-broad distinction that confused us:
- **Welcome:** "You're following all our sources. Use `/follow-tag <topic>` to
  focus your digest — chat can still search everything."
- **On `/follow-tag`:** "Digest now focused on: ai, java. (Chat still searches all
  your sources.)"
- **On an empty/narrowed digest:** a hint back to `/follow-tag`.

Guidance copy is reviewed as carefully as the code, and lives in the localization
bundle (en + cs, D43).

## Schema / build

- **ADD `source.source_origin`** (`bootstrap` | `user`); the bootstrap loader sets
  `'bootstrap'` — mirror the existing `tag.source_origin` pattern. Everything keys
  off it.
- **`source_subscription.added_by` already exists** (no migration).
- **Query change:** RAG/digest scoping moves from `source_id IN (source_subscription …)`
  to `source_origin='bootstrap'` (minus my exclusions) `OR source_id IN (my custom subs)`.

## Decisions to reconcile in the spec edit

- **Supersede** `commands.md:622` ("no auto-subscribe at registration / explicit
  opt-in") → implicit-all-bootstrap. New decision ID in `decisions.md`.
- **`/follow-all-sources`** — likely retires, or becomes "reset my exclusions."
- **`commands.md:608–611`** deferred `added_by` note — now moot (custom privacy is
  handled by scoping; the column already exists).
- **D7** (global source rows) — unchanged; `source_origin` layers on top.
- **Determinism caveat:** tag-selection leans on tagger quality (deterministic
  floor = `bootstrap_tags` fallback). Already true today; note it, don't fix it.

## Next

v1-scoped. **M1-620 is now merged** — next: write the spec amendment
(`commands.md` + `decisions.md`) and file the ticket(s), using this note as input.
Cross-linked from `docs/plan/v1-verification-truth.md` §6b (v1 subscription-UX gap)
and `docs/plan/future-features.md`.

The pre-release **user-facing message audit** captured here has moved to its
proper home — `docs/plan/v1-verification-truth.md §6/§9` + handoff §5 (release
gates); it's release-wide, not subscription-specific.
