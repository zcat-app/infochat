---
name: audit-claims-vs-sequences
description: "A doc audit that checks claims in isolation misses SEQUENCE bugs — walk every numbered happy path as an actual state machine, because step N's correctness depends on the state step N-1 left behind."
metadata:
  type: feedback
---

The 2026-07-24 pre-release guide audit checked README/SETUP/ADMIN/USER/OVERVIEW/
CONTRIBUTING claim-by-claim — every command token against the canonical index, every
link, every flag, every config key, every default. All passed. It still shipped a wrong
README quick-setup **step 2**: "connect, then send `/help`". Each *token* in that
sentence is real (`/help` exists, the bootstrap admin is real, no invite code is
genuinely needed) — the sentence is only wrong **in position**, because at that point in
the sequence a SimpleX operator has no `users` row, so the router's unknown-contact
branch answers "you need an invite" instead. It was caught later, re-reading the file
for a *cognitive-load* question, not by any of the accuracy checks.

**Why:** claim-level verification is stateless. It asks "is this true?" but never "is
this true *here*, given what the previous steps did or did not establish?" Onboarding,
bootstrap, migration and recovery docs are exactly where that gap lives, because their
whole content is ordering, and the reader has no way to detect a step that is true in
general but wrong at that moment.

**How to apply:** for any numbered/ordered procedure in a doc, do a second pass that
walks it as a state machine — track what exists after each step (rows, files, config,
containers, identities) and ask at every step whether the instruction is valid *in that
state*. Where the branch depends on a variant (adapter, OS, profile), walk each variant
separately; the SimpleX leg was wrong while the Signal leg was right, and one combined
sentence hid it. Also check the *terminal* state for a missing security step — the same
pass found the README never says to unset `INFOCHAT_SIMPLEX_ADMIN_TOKEN`.

`docs/process/guide-accuracy-audit.md` does NOT yet encode this (its method is
per-file falsification of claims, and its per-file agent prompt asks for
"order-of-operations" only as one item in a flat claim list). Worth adding a
"walk the sequences" pass to §Method.

Related: [[verify-subagent-quotes-before-pinning]] (the same failure family — a check
that looks rigorous but is not actually adversarial to the thing that breaks).
