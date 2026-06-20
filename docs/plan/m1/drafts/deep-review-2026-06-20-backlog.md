# Deep-review full (2026-06-20) — backlog (items not ticketed)

Source: `.reviews/deep-review/full-2026-06-20-1530/` (architecture + 6 module
reports + `00-summary.md`). The run found 0 critical / 0 high / 2 medium / 7 low.
All findings were re-verified at source 2026-06-20 (falsify-before-ticket pass).

Ticketed 2026-06-20:

- **M1-407** — provider F1 (medium, SECURITY): GetReferencesTool result-byte budget.
- **M1-408** — messaging F1 (medium, latent): SignalGroupHandler fall-through so a
  co-delivered bot mention isn't dropped.
- **M1-409** — collector F1 + llm-adapter F1 (low, CT1 guard-asymmetry): feed
  item-count caps + timeout-ms startup validation.
- **M1-410** — core F1 + provider F2 (low, SQL source-of-truth): ProcedureOnlyAction
  parity test + export classify-by-column-type.
- **M1-411** — ssrf F2 + messaging F3 (low, simplification): inline isIpv4Mapped +
  single-source chunker fence state.

## Dropped — premise failed verification (do NOT ticket as written)

- **ssrf F1** (`SsrfGuardedHttpClient.java:602-606`, "`addresses == null` half is
  §7-forbidden defensive code, drop it"). Verification verdict: **PARTIAL → drop.**
  The `== null` half is, by the letter of §7, drift against an internal seam contract
  (the seam is a same-process `Function<String, List<InetAddress>>`, not a
  §7-enumerated system boundary, and its bare return type declares non-null under the
  package null-marked default). BUT the report's load-bearing justification — "NullAway
  enforces this contract at compile time, so the check is unnecessary" — appears
  **false**: the NullAway config does not enable JSpecify mode, and in default mode
  NullAway does not check the nullability of a generic type argument / lambda return
  body, so a null-returning seam would compile silently and NPE at runtime. Removing
  the null check would therefore trade away a real silent-NPE-vs-typed-exception guard,
  not delete dead code. The remediation as written is contested; not filed.

## Observation surfaced during verification (investigate separately, not a finding)

- **NullAway JSpecify mode may be off.** The ssrf F1 verification claimed the NullAway
  flag line lacks `-XepOpt:NullAway:JSpecifyMode=true`, which would mean generic
  type-argument nullability (`Function<…, List<InetAddress>>` returns, `List<@Nullable
  String>`, etc.) is NOT machine-enforced — in tension with CLAUDE.md §"Method
  parameter contracts" ("JSpecify (not JetBrains) is the v1 annotation source — the
  type-use semantics let `List<@Nullable String>` express …"). This is a single
  agent's claim about pom config, NOT independently re-verified, and its scope (build
  configuration across all modules) is far larger than the finding it came from. If
  confirmed, it is its own decision/ticket about whether v1 actually gets the
  type-use enforcement the spec assumes. Flagged here so it isn't lost; deliberately
  not turned into a ticket off one unverified transcript claim.

## Verified valid but already covered / no action

- Architecture lens: **0 findings** — recorded seven verified contract surfaces
  (module DAG enforcement, NOTIFY producer/consumer agreement, capability-flag
  fail-fast, audit-verb closure, property/SPI/trust-boundary consistency). Nothing to
  ticket.
- Several module reports deferred structural observations to the architecture lens
  (duplicated adapter scaffolding across the two production adapters; single-
  `InetAddressResolverProvider` classpath invariant; chat-tool-SPI budgeting seam).
  The architecture lens reported no findings on them. The chat-tool-SPI budgeting
  seam idea is recorded as an `Alternatives considered:` direction in M1-409/M1-407,
  not as standalone scope.
</content>
