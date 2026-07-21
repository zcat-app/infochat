# Cross-examination report

Run directory: `docs/plan/m1/redteam-multi/M1-652-2026-07-21`
Auditors: claude, opencode, codex

## Summary

- 2 distinct finding cluster(s) across all auditors.
- 0 corroborated (flagged by >=2 auditors).
- 2 single-auditor -- each is either a real gap the others missed or a false positive; see the per-cluster detail and the falsification candidates section.
- Per-auditor raw finding counts: {'opencode': 1, 'codex': 1}.

## Per-auditor verdicts

- **claude**: CLEAN (0 finding(s))
- **opencode**: FINDINGS (1 finding(s))
- **codex**: FINDINGS (1 finding(s))

## Finding clusters (side-by-side)

| # | Category | Primary location | claude | opencode | codex | Severity (max) | Attribution |
|---|---|---|---|---|---|---|---|
| 1 | INJECTION | `DigestRetryService.java:157-195` | -- | -- | high | high | codex-only -- needs review |
| 2 | INFO-LEAK | `DigestWorker.java:255` | -- | low | -- | low | opencode-only -- needs review |

## Per-cluster detail

### Cluster 1: INJECTION @ `DigestRetryService.java:157-195`

**codex** (severity: high, fix-class: input-sanitization)

- PROMISE: "Before any LLM-generated text is delivered to a user, the candidate output is passed through a deterministic outbound regex pass that strips or refuses output containing admin command strings ... The sanitizer applies to the full set of LLM-authored output surfaces: ... periodic group digests, /retry re-rolls ..."
- GAP (first 400 chars): DigestRetryService.java:157-195 reads persisted RenderedSection content and sends the missing sections through DigestDelivery without any sanitizer invocation; DigestDelivery.java:125-137 copies section.text() directly into OutboundMessage and hands it to the adapter. The new digest_section persistence path (DigestSectionRepository.java:96-113) stores the exact rendered bytes, but the diff supplie...


### Cluster 2: INFO-LEAK @ `DigestWorker.java:255`

**opencode** (severity: low, fix-class: other)

- PROMISE: docs/spec/security.md §Secrets handling → "User content in
      exceptions" commits: "Exception messages and stack traces emitted
      via the application logger MUST NOT contain user-authored prose
      (chat-mode message bodies, post bodies, saved-post annotations,
      command arguments). The application provides a SafeLog utility
      that drops the exception message body, retains only th...
- GAP (first 400 chars): The diff introduces two new catch-and-log sites that pass the
      raw Throwable straight to the JBoss/SLF4J logger instead of
      routing through SafeLog:
        - DigestWorker.java:255 — `LOG.warnf(persistFailure, ...)`. The
          guarded call is `DigestSectionRepository.replaceSlotSections`
          (DigestWorker.java:251), whose batch INSERT binds the
          `digest_section.content...


## Single-auditor findings (falsification candidates)

Each finding below was reported by exactly one auditor. Either the others missed a real gap, or this auditor produced a false positive. A v2 synthesizer subagent would re-audit each against the threat model; this v1 surfaces them for human review.

- **codex-only**: INJECTION @ `DigestRetryService.java:157-195` (severity high). See `verdict-codex.txt` for full PROMISE/GAP/REPRO.
- **opencode-only**: INFO-LEAK @ `DigestWorker.java:255` (severity low). See `verdict-opencode.txt` for full PROMISE/GAP/REPRO.

