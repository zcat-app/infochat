---
name: release-state-source-ranking
description: "docs/plan/v1-verification-truth.md is the authoritative release-state record — read it first. Rank sources: live DB/config/git > committed docs > scratch handoffs > memory, and always read the date."
metadata:
  type: reference
---

`docs/plan/v1-verification-truth.md` is the single provenance-tagged, dated
record of what is verified versus still owed for the v1 release. **Read it
first** for any "what's tested / what's left / is X done" question — do not
reconstruct release state from memories or scratch handoffs. Every claim in it
is tagged VERIFIED / ATTESTED / DERIVED with a source and a date. When state
changes, update that doc (bump its as-of date, retag the claim) rather than
spawning a new snapshot somewhere else.

**Source-ranking rule (the durable lesson):** live DB / running config / `git`
**>** committed docs (`docs/plan/`, `docs/spec/`) **>** scratch handoffs **>**
accumulated memory. **Always read the date.** A whole session went wrong by
leading with six-day-old memories plus a five-day-old scratch handoff and
stating them as current fact.

Two corollaries that keep biting:
- A design note under `docs/design/` describes what was decided, not what
  shipped; those files mix normative requirements with as-built description in
  the same voice. A resolving spec anchor proves the section exists, never that
  the code matches it.
- Superseded handoffs are actively harmful once their claims invert. Mark them
  STALE in place rather than leaving them to be re-inherited.

Companion: the post-v1 feature backlog is `docs/plan/future-features.md`.
See also [[reviewer-is-conformance-not-correctness]].
