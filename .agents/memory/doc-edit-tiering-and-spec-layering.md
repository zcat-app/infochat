---
name: doc-edit-tiering-and-spec-layering
description: Doc-tier rule for the ticket flow (plain docs edit directly; test-fixture docs need the flow; spec approval is separate) and the one-way spec ← design ← measurement layering axiom
metadata:
  type: feedback
---

User rulings 2026-08-16, after the M1-856 analyst spawn wasted a review
cycle on a two-sentence record fix:

**Doc-tier rule.** The ticket flow exists for code/tests/migrations/
spec-with-code — for pure-doc edits it is ceremony. Three tiers:

1. **Plain docs** (spec/design/measurement/process prose that no test
   reads): edit directly on main with the non-ticket prefix (`spec:`,
   `process:`, `docs:` — see workflow §Non-ticket commits); a clear
   commit message is the audit trail. Do NOT spawn analyze/review for
   these. `docs/measurement/**` records are this tier (precedent:
   `docs: correct direct-chat-e2e record tie tuples`, 7715576e).
2. **Docs that are test inputs** (parity fixtures: spec sections
   `DocumentedConfigKeyParityTest`/`CommandCatalogueParityTest` parse,
   config-key docs, anything a test asserts on): behavior changes in
   disguise — the build can go red; they need the verify leg and the
   flow. Rule of thumb: if a test reads the file, it is not a doc edit.
3. Code/config/scripts: full flow, unchanged.

Caveats that survive tiering: spec wording still needs the user's
explicit approval before it lands (engineering-rules §12 — approval is
about the WORDING, independent of the commit mechanics); measurement
records still need their derivation logged in the campaign DECISIONS.md
(decision-20/21 precedent — the gitignored log is the audit trail for
every corrected number).

**Spec layering axiom (one-way flow).** Spec is the source of truth and
states rules only — it NEVER references a measurement record (verified
2026-08-16: grep docs/spec/ for measurement references returns zero).
Design docs may cite measurements as decision inputs. Measurement
records are terminal evidence: nothing normative points at them;
downstream work (amendments, registries) READS them at ticket time.
Spec → design → measurement, arrows never reverse.

Related: [[doc-only-edits-skip-verify]], [[pre-registration-free-variable]]
