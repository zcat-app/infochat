# Deep-review v4 — backlog (lows not ticketed 2026-06-09)

Source: `deep-code-review/v4/UNIFIED-REPORT.md` §3 "Misc verified lows" +
§5 "Backlog" row. The v4 HIGH/MEDIUM findings and the named low sweeps were
cut into tickets **M1-262..M1-281** on 2026-06-09. This file carries what
deliberately did NOT get a ticket, so it isn't lost. Items marked
**[unverified]** were single-source and not individually deep-verified by the
unified report — verify the premise at the source file before drafting any
ticket from them (premise-fail rule).

## Verified lows, no ticket yet

- `preview()` labels a char count "bytes" (fable5; `:200`); fixing touches a
  pinned test.
- Silent body-cap clamp with no log (`clampBodyCapBytes`, opus-47).
- `scope_preferences.digest_enabled` dead column — zero readers; all reads
  hit `groups`. Removal = migration; bundle with the next schema-touching
  ticket.
- `AuditLogWriter` open-codes two `setNull(OTHER)` blocks (opus-47).
- Mention-strip `start+length` int overflow — SIOOBE contained by the
  dispatch-survival catch; single message dropped (opus-48).
- `SignalGroupHandler` typed-accessor CCE at boundary — contained by the same
  outer catch (opus-47, downgraded).
- `Stage1Pipeline` watchdog samples the clock on every `charAt`; `Redactor`
  shows the interval-sampling pattern to copy (fable5).
- `BlueskyFetcher` inline `.orElse(5)` profile default (fable5).
- NOTIFY payloads parsed by regex — closed contract, row-truth + reconciler
  recover; latency/robustness note only (deepseek#F5).
- `AdapterRegistry` mixed config access styles (deepseek#F7).
- Window regexes `hdw` vs `hdwm` — only partially duplicate; unify with care
  (deepseek#F8).
- `ListSavesTool` parses model-supplied `window` without clamping to its own
  `WINDOW_MAX` (gpt-55#L-06) — LLM tool args are a trust boundary; small fix,
  good rider on the next chat-tools ticket.
- LLM error-body previews in WARN+exceptions — bounded to 200 chars and
  catalogue-scanned; residual small (gpt-55#M-06, downgraded).
- Test sources outside ErrorProne/NullAway (gpt-55#L-08) — known deliberate
  M1-164 scope; policy item, revisit post-M1.

## Plausible, [unverified] — verify before ticketing

Subprocess `stop()` vs scheduled-restart orphan race · SimpleX
close/reconnect TRANSIENT stranding · EmbeddingWorker dead semaphore ·
OutboxRehydrator unbounded startup pass · copy-pasted fetcher/JSON-helper
clones · Kraken/Bitfinex duplicate conversions · EmbeddingWorker full-body
read · Stage2VerdictHandler branch similarity · RSS MAX_ITEMS post-add ·
JSON-by-concat sites (`ChatAgent:394` etc.) · GetPostTool truncation budget ·
duplicate `tokenize()`/`UserRow` · SimpleX dead typing/handle surfaces.

## Recorded wont-fix (do not re-ticket)

See `deep-code-review/v4/UNIFIED-REPORT.md` §4: rejected/reclassified
findings (incl. opus-48's DNS-cache divergence and mimo's cloud-metadata
HIGH) and the wont-fix-lean verified facts (Redactor in-band sentinel,
params-array mutation, per-read virtual-thread in `readBounded`, etc.).
