# M1-750 redteam-multi disposition

Audit: 2026-08-03, auditors claude / codex / opencode (3/3 AVAILABLE),
target `--diff` = working-tree-vs-fork-point of branch
`m1/M1-750-source-language-plumbing-lang` (0 commits on branch; the
uncommitted implementation).

## Verdict

**CLEAN (3/3 auditors).** No findings, corroborated or single-auditor.

## Out-of-model observations (advisory; no auto-filed tickets)

1. **claude — operator-vs-caller framing asymmetry.** The 07-deployment.md
   `language` row says "DECLARED by the operator" while the commands.md
   amendment says "declared by the caller". Substance is identical (D29:
   declared, never inferred; both entry points validate against the same
   reviewed registry); the words differ because the bootstrap correction
   path is operator-side while `/add-source` accepts the declaration from
   any permitted caller. **No ticket** — no model gap.
2. **opencode — user-influenced translation-cost surface.** A non-admin
   caller's `--lang cs` on a user-chosen feed flips on per-post LLM
   translation (M1-749's IngestTranslationWorker) for that feed; before
   M1-750 no source could be declared non-English, so this is a new
   user-influenced collector-side LLM-cost surface. `security.md` §Rate
   limiting commits only provider-side buckets, so it is out of model, not
   a FINDING. Bounds that exist today: the per-user `/add-source` hourly
   bucket (M1-705), probation, and feed-volume reality. **Recommendation:
   deliberate residual risk for now** — note a follow-up ticket for a
   collector-side translation-spend bound (or `--lang` restricted to
   bot-admins) if the ingest-translation wave (M1-749 follow-ups) takes
   shape; not a blocker for this ticket, which only opens the declaration
   surface D29 already spec'd.
3. **opencode — bootstrap-side hardening asymmetry.** The bootstrap
   parser's `resolveLanguage` lower-cases but does not trim/NFKC-normalize
   (the provider parser trims), so a bootstrap entry declaring `" cs "` or
   a fullwidth code fails startup rather than defaulting. Fail-closed,
   operator-side input only. **No ticket** — the stricter bootstrap side is
   acceptable (fail-fast at startup is the section's documented posture);
   if a later bootstrap touch adds trimming, fold it in there.

## Record

- Verdicts: `verdict-{claude,codex,opencode}.txt` (durable)
- Cross-examination: `cross-examination.md` (durable)
- Regenerable scratch in this directory is gitignored (prompt/reply/inv/
  porcelain/preflight, diff.patch).

Ticket frontmatter: `redteam_audits` entry appended (CLEAN, 0 findings,
2 out-of-model).
