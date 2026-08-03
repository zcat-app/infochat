## RE-AUDIT ADDENDUM (round 3 — appended by the driver after rendering)

This audit is a RE-AUDIT (round 3) of M1-747. Two prior audits exist and
their verdict files are inside the diff you are reading:

- `docs/plan/m1/redteam/M1-747-2026-08-03.md` — round 1: 4 findings
  (1 medium/INJECTION cache-read rewrite, 1 low/INJECTION source-language
  sink, 1 low/INFO-LEAK cross-scope cache, 1 low/DOS pair).
- `docs/plan/m1/redteam/M1-747-2026-08-03-r2.md` — round 2: verified all
  four round-1 findings CLOSED against the then-current diff and raised 2
  new low/INJECTION findings (substitution-order dependence on a
  caller-side validator; DisplayHeadline's primitives promoted to public).

The round-2 findings were remediated in commit c17d6636, which is the HEAD
of this diff range. That remediation is NEW, unaudited surface: audit it
as such.

Instructions:
- Do NOT assume the round-2 findings are closed because remediation was
  attempted — verify each against the current diff.
- Do NOT re-report a finding that is genuinely closed; re-reporting
  duplicates the record without adding information.
- Focus your adversarial energy on what the remediation itself changed:
  the single-pass prompt renderer in LlmTranslationProvider, the
  DisplayHeadline.prepareTranslatedHeadline composite and the
  visibility narrowing, and the TranslationPipeline display-hit leg.
- Be willing to return CLEAN if the diff genuinely warrants it. An audit
  that manufactures a finding to justify itself is worse than no audit.
