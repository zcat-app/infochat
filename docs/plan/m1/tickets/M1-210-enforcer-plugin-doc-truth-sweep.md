---
id: M1-210
title: "Module-DAG enforcement + doc/config-truth sweep (docs say what the build does)"
status: pending
created: 2026-06-07
last_updated: 2026-06-08
blocked_by: []
files_budget: 18
files_scope:
  - pom.xml
  - docs/design/09-reference.md
  - docs/design/07-deployment.md
  - docs/design/01-architecture.md
  - docs/design/05-llm-and-embeddings.md
  - docs/design/02-schema.md
  - docs/spec/deployment.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/AdapterRegistry.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/eval/stage2/StartupReleaseOnStage2FailureWarn.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/summary/EligiblePostQuery.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/StatusCommandHandler.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/assets/AssetSnapshotFetcher.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/assets/AssetSnapshotReader.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/Fetcher.java
  - infochat-core/src/main/java/app/zcat/infochat/core/ingest/StreamSource.java
  - infochat-core/src/main/java/app/zcat/infochat/core/audit/RedactionHook.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/config
complexity: medium
risk: medium
round_cap: 3
security_relevant: false
migration_touch: false
out_of_scope:
  - relocating the JDBC/Quarkus-touching classes out of infochat-core — adjudicated direction is doc-fix, NOT class relocation (UNIFIED Tier-A call, opus-48 over mimo); the 09-reference core row is amended to the truth
  - creating a CI pipeline — the "verified in CI" half of the 09-reference claim is scoped down or amended, not made true by building CI here
  - EligiblePostQuery's missing SQL LIMIT and result handling — M1-194's (same file: only the profile-label defaultValue line changes here; serialize or coordinate with M1-194)
  - ModelTask.java code changes — the keySegment leg fixes design 05 to match the shipped "chat" segment, not the reverse
  - V5's stale verb-catalogue comment block — applied migrations are immutable (Flyway checksums); the living catalogue is design 02-schema §2.1.8, which this ticket updates
  - the TranslationProvider row of 09-reference's module table — M1-213's placement decision owns it (landed, merged c00ae01; do not re-touch that row)
  - the rest of design 05 §5.1's package tree (embedding-provider impl names, observability classes, etc.) — the §5.1 leg fixes ONLY the three TranslationProvider rows surfaced by M1-213, not a full tree-vs-code reconciliation
  - CLAUDE.md's "infochat.profile" concept phrasing — process-file wording, deliberately retained as the concept name per InfochatProfile's javadoc
acceptance:
  - "The 09-reference module-DAG ban is build-enforced: adding a dependency from infochat-collector on infochat-messaging-adapter fails the build with a clear error (maven-enforcer banned-dependencies or equivalent build-time mechanism; the failure demonstration is argued in the commit message), and 09-reference.md's enforcement sentence states exactly what is now true — POM-enforced, with the CI half either dropped or rephrased as future (no CI exists)"
  - "09-reference.md's infochat-core row no longer claims \"Pure Java; no Quarkus, no I/O\" — it describes the actual contents (JDBC-touching notifier, Quarkus-touching lock guard) and what the module still promises (no user-facing surface, no messaging dependency)"
  - "All readers of infochat.profile.label resolve the same default: the three @ConfigProperty declarations (today \"unknown\" in StartupReleaseOnStage2FailureWarn vs \"laptop\" in EligiblePostQuery and StatusCommandHandler) agree, pinned by a named test or a shared constant"
  - "The infochat.assets.refresh.* keys resolve identically in both services: today AssetSnapshotFetcher declares them with no defaultValue while AssetSnapshotReader defaults the same keys to \"90\" — after this ticket both services are either equally required or equally defaulted, pinned by a named test or shared constant"
  - "No design doc instructs setting an infochat.profile= property: design 07-deployment's runbook (:103) and reference properties (:135), design 01-architecture (:629), and design 05 (:15) all name the actual mechanism (QUARKUS_PROFILE / quarkus.profile), so following the runbook no longer produces the InfochatProfile startup crash (\"No known infochat profile in active Quarkus profile chain\")"
  - "Design 05's profile enumeration says remote-llm, not the stale remote"
  - "Design 05's per-task property keys for the chat agent use the shipped key segment (ModelTask.CHAT_AGENT keySegment is \"chat\"; design 05 :64-65 says infochat.llm.chat-agent.*)"
  - "Design 05 §5.1's SPI-overview package tree shows the TranslationProvider classes where they actually live, consistent with the merged M1-213 placement decision (docs/spec/llm.md §SPI shape): the TranslationProvider SPI interface (:29) sits under infochat-messaging-adapter, NOT infochat-llm-adapter/api/; LlmTranslationProvider (:40) sits under infochat-provider (translation/), NOT infochat-llm-adapter/impl/; and the NoopTranslationProvider row (:41) is dropped — no such class ships (the scope-language='en' passthrough is handled in the translation pipeline per llm.md §Translation flow, not a Noop SPI impl)"
  - "AdapterRegistry's gate-order comment matches the gate count (today \"The six gates\" at :60 vs \"Gate 7\" at :227 — renumber or make the comment count-free)"
  - "docs/spec/deployment.md's Flyway-ownership statements match the shipped shape: production Provider does not run migrations (quarkus-flyway is test-scoped in Provider; the operator runs Collector first) — the \"Both services run Flyway on startup\" sentences (:39, :146) and the concurrent-migration paragraph (:45) are reconciled to that"
  - "Design 02-schema §2.1.8's verb table matches the AuditAction enum: the 25 enum constants missing from the table (48 in the enum vs 23 in the table at draft time) are added — the section's own rule says \"extending the catalogue is a design-note edit\""
  - "Fetcher and StreamSource javadocs describe sourceId as the per-tick opaque dispatch token (adjudicated: NormalizedPost's javadoc is the correct one; FetchScheduler passes row.dispatchKey(), not the source UUID — today Fetcher :25 and StreamSource :27 claim sourceId is the source.id)"
  - "RedactionHook's javadoc carries the fail-closed-on-regex-timeout contract the spec commits to (security.md §Secrets handling: \"The redactor is fail-closed on regex timeout … a timed-out match treats the whole field as redacted rather than emitting it raw\" — the mechanism exists in Redactor; the SPI javadoc is silent about it)"
  - "mvn -B clean verify from the repo root exits 0"
test_plan:
  adds:
    - infochat-provider/src/test/java/app/zcat/infochat/provider/config
  preserves:
    - all tests currently green on main
spec_refs:
  - docs/spec/deployment.md §Bootstrap behavior on startup
  - docs/spec/architecture.md §Service split
decision_refs:
  - D27
reviews: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
---

# M1-210: Module-DAG enforcement + doc/config-truth sweep

## Context

The truth-alignment grouping (unified findings A1, A2, A6, A7, A9,
A10, A11, L9, the design-05 stale `remote` label, the D13 verb-table
drift, the sourceId javadoc adjudication, and the RedactionHook
contract note — `deep-code-review/v2/UNIFIED.md` §2). Re-grounded
2026-06-07; every leg's mechanism confirmed on disk:

1. **A1.** 09-reference claims the collector↛messaging-adapter ban is
   "Enforced by the parent POM and verified in CI" — zero `enforcer`
   hits in any pom, no `.github` directory.
2. **A2.** 09-reference's core row claims "Pure Java; no Quarkus, no
   I/O" — false (ThrottledAdminNotifier does JDBC in core).
   **Adjudicated direction (binding): doc-fix, not relocating classes.**
3. **A6.** `infochat.profile.label` defaults diverge: "unknown"
   (StartupReleaseOnStage2FailureWarn :82) vs "laptop"
   (EligiblePostQuery :67, StatusCommandHandler :61).
4. **A7.** Design docs instruct `infochat.profile=...` — a key the
   code does not read (InfochatProfile :26-33 documents "Why no
   separate infochat.profile key"; resolution reads the Quarkus
   profile chain and `resolveOrThrow` crashes startup if the runbook
   is followed). Re-grounding widened the leg beyond the audit: design
   01-architecture :629 and design 05 :15 carry the same key.
5. **A9 (stray, folded here).** Asset-refresh config keys duplicated
   across services with different defaults.
6. **A10.** "The six gates" comment vs Gate 7.
7. **A11.** Spec deployment.md says both services run Flyway; the
   Provider's properties header documents the deliberate opposite.
8. **L9.** ModelTask.CHAT_AGENT keySegment is "chat"; design 05 writes
   `infochat.llm.chat-agent.*`. Fix the design doc.
9. **D13 (constrained).** V5's verb-comment catalogue is a frozen
   snapshot (applied migrations immutable); the living catalogue is
   design 02-schema §2.1.8, which self-declares "extending the
   catalogue is a design-note edit" yet is 25 verbs behind AuditAction
   (23 vs 48, re-counted at draft time, zero design-only verbs).
10. **sourceId (notes.md adjudication #31, folded here).**
    Fetcher/StreamSource javadocs claim `sourceId` is the `source.id`;
    NormalizedPost and FetchScheduler prove it is the per-tick dispatch
    token. Javadoc-in-code = code change; the adjudicated direction is
    fixing Fetcher/StreamSource.
11. **RedactionHook contract (opus-47 stray, folded here).** The spec
    commits the audit redactor to fail-closed-on-regex-timeout;
    Redactor implements it (TIMEOUT_SENTINEL), but the RedactionHook
    SPI javadoc never states the contract.
12. **Design 05 §5.1 TranslationProvider tree (M1-213 follow-up).**
    M1-213 settled TranslationProvider's home as infochat-messaging-adapter
    (presentation-layer SPI, decision D29) and fixed spec llm.md §SPI shape
    + 09-reference's module row, but left design 05 §5.1's package tree
    still drawing the SPI under infochat-llm-adapter/api/ (:29),
    LlmTranslationProvider under infochat-llm-adapter/impl/ (:40, really
    infochat-provider/translation/), and a NoopTranslationProvider row
    (:41) for a class that ships nowhere. Doc-only; the code already
    matches the merged spec (zero llm-package imports of the interface).

## Acceptance

See frontmatter.

## Out-of-scope

See frontmatter.

## Notes

- Source: `UNIFIED.md` §3 T30 under `deep-code-review/v2/`, plus three
  folded strays (A9; adjudication #31; the RedactionHook javadoc gap
  from the deepseek/opus-47 doc bulk — the remaining doc-bulk items
  are recorded as drops in the batch summary).
- **Dependency approval required before implementation:** the
  maven-enforcer-plugin (or chosen equivalent) is a build-plugin
  addition — per the project dependency rule it needs explicit user
  approval first.
- File overlap: EligiblePostQuery is in M1-194's files_scope —
  serialize or coordinate (this ticket touches only the defaultValue
  literal). AdapterRegistry is in M1-178's orbit (bootstrap-admin
  comment update) — the gate-count comment leg is disjoint from
  M1-178's comment, but rebase after M1-178 if it lands first.
  09-reference.md's TranslationProvider row was landed by M1-213
  (merged c00ae01); do not re-touch it. The §5.1 leg added here is the
  remaining design-05 half of that placement fix.
- The Quarkus-touching lock guard named by the core-row leg is
  AbstractInstanceLockGuard; the JDBC class is ThrottledAdminNotifier
  (both stay where they are — doc-fix direction).
