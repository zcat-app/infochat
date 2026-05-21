---
id: M1-050
title: "Process fix E: JSpecify parameter contracts (boundary classes + lint)"
status: pending
created: 2026-05-21
last_updated: 2026-05-21
blocked_by: []
files_budget: 11
files_scope:
  - pom.xml
  - scripts/lint-contracts.py
  - CLAUDE.md
  - docs/process/engineering-rules-verbatim.md
  - docs/process/reviewer-prompt.md
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
complexity: medium
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
decomposed_from: M1-047
out_of_scope:
  - retroactive annotation of every public method across the codebase — v1 scope is the 4 named boundary classes (InboundRouter, MessagingAdapter SPI, CommandHandler SPI, BundleLoader). A follow-up ticket can widen to the *Service classes, handler implementations, and infochat-collector boundaries
  - annotating private/package-private methods — convention is "public/protected MUST annotate; internal MAY inherit default" per the engineering rule. Lint enforces on public only
  - replacing JSpecify with JetBrains @NotNull/@Nullable — user picked JSpecify on 2026-05-21
  - runtime null-check insertion based on annotations — JSpecify is compile-time/static-analysis only; no behavior changes at runtime
  - test pyramid refactor (M1-049 territory)
  - verified_stays_green lint check (M1-048 territory)
  - any change to handler implementations (AddSourceCommandHandler, SummaryCommandHandler, HelpCommandHandler) — the SPI (CommandHandler) is annotated; concrete handlers inherit the annotated signature without their own annotation work in this ticket
  - any change to existing tests — annotations are compile-time; existing tests stay byte-for-byte unchanged
acceptance:
  - "Parent pom.xml adds the JSpecify dependency: `org.jspecify:jspecify:1.0.0` with `<scope>provided</scope>` (compile-time only; not in runtime classpath). Verify: `grep -E '<artifactId>jspecify</artifactId>' pom.xml` returns ≥1 match AND `grep -A2 '<artifactId>jspecify</artifactId>' pom.xml | grep -E '<scope>provided</scope>'` returns ≥1 match"
  - "New script `scripts/lint-contracts.py` exists and is executable. Verify: `test -f scripts/lint-contracts.py && python3 scripts/lint-contracts.py --help` exits 0 AND prints a usage line containing the script name"
  - "scripts/lint-contracts.py accepts a list of `.java` file paths as args, parses each for public/protected method declarations, and for every reference-type parameter (any non-primitive parameter type) checks for either an `@NonNull` or `@Nullable` annotation from `org.jspecify.annotations`. Reports FAIL with the file path + method name + parameter name for each missing annotation. Verify: running `python3 scripts/lint-contracts.py infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java` after the retroactive pass exits 0 (zero findings on the now-annotated boundary)"
  - "scripts/lint-contracts.py supports a `--baseline <file>` flag that reads a list of `path:method` entries to grandfather (suppress findings for). The baseline file format is one `path:method` per line, comments after `#`. Verify: `python3 scripts/lint-contracts.py --baseline scripts/lint-contracts-baseline.txt <some-un-annotated-file>` exits 0 when every finding is in the baseline. (The baseline file itself is NOT in files_scope; it is created in this ticket's commit but only contains comments + future-use schema. The baseline can be populated by a follow-up ticket as the retroactive pass widens.)"
  - "CLAUDE.md §Engineering rules adds a new subsection (or extension of §'No defensive code') titled 'Method parameter contracts' (or equivalent). The rule text states: 'Method parameter contracts MUST be explicit. Every reference-type parameter on a public method declares nullability — either via annotation (@NonNull/@Nullable from org.jspecify.annotations) or via javadoc @param. Public/protected methods MUST annotate; internal/package-private methods MAY inherit the default (non-null-assumed). Validation at system boundaries still uses explicit null-checks per the existing No-defensive-code rule.' Verify: `grep -cE 'org\\.jspecify\\.annotations|@NonNull.*@Nullable|parameter contracts' CLAUDE.md` returns ≥1 match"
  - "docs/process/engineering-rules-verbatim.md adds the verbatim rule text from CLAUDE.md (so the reviewer's canonical source contains the same statement). Verify: `grep -cE 'org\\.jspecify\\.annotations' docs/process/engineering-rules-verbatim.md` returns ≥1 match"
  - "docs/process/reviewer-prompt.md adds a new check that requires every new public method (i.e. methods added in the diff under review) on a public/protected reference-type parameter to carry @NonNull or @Nullable. Verdict on missing: REWORK with the check name (e.g. `PARAMETER-CONTRACT-CHECK`). Verify: `grep -cE 'PARAMETER-CONTRACT-CHECK|@NonNull.*@Nullable|jspecify' docs/process/reviewer-prompt.md` returns ≥1 match"
  - "InboundRouter.java's public method `onMessage(InboundMessage, String)` is annotated: both parameters declared `@NonNull` per the SPI contract (the router never receives null). Verify: `grep -E 'onMessage\\s*\\(\\s*@NonNull\\s+InboundMessage\\s+\\w+\\s*,\\s*@NonNull\\s+String\\s+\\w+\\s*\\)' InboundRouter.java` returns ≥1 match"
  - "MessagingAdapter.java's SPI methods are fully annotated on all reference-type parameters. Specifically the `send(ScopeRef, OutboundMessage)` method (or whatever the canonical send signature is on main): both parameters @NonNull. Verify: `grep -E '@NonNull\\s+ScopeRef' MessagingAdapter.java` returns ≥1 match AND `grep -E '@NonNull\\s+OutboundMessage' MessagingAdapter.java` returns ≥1 match"
  - "CommandHandler.java's SPI `handle(ScopeRef, String)` (or canonical signature on main) is annotated: both parameters @NonNull. The `name()` method (which returns String and takes no params) is unaffected. Verify: `grep -E '@NonNull\\s+ScopeRef' CommandHandler.java` returns ≥1 match AND `grep -E '@NonNull\\s+String' CommandHandler.java` returns ≥1 match"
  - "BundleLoader.java's public `get(String)` method (or canonical signature) annotates the String key as @NonNull and the return type as @NonNull (annotate-return is a one-line addition consistent with the JSpecify convention). Verify: `grep -E '@NonNull\\s+String' BundleLoader.java` returns ≥1 match (the parameter annotation is the load-bearing assertion; return-type annotation is encouraged but not strictly required by this check)"
  - "Running `python3 scripts/lint-contracts.py` on the 4 retroactively-annotated boundary files exits 0. Verify: `python3 scripts/lint-contracts.py infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandHandler.java infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java` exits 0"
  - "mvn -B clean verify exits 0 (annotations are compile-time; provided-scope dependency does not affect runtime classpath; no production behavior changes)"
test_plan:
  adds:
    - scripts/lint-contracts.py
  modifies:
    - pom.xml
    - CLAUDE.md
    - docs/process/engineering-rules-verbatim.md
    - docs/process/reviewer-prompt.md
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
    - infochat-messaging-adapter/src/main/java/app/zcat/infochat/messaging/MessagingAdapter.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/CommandHandler.java
    - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleLoader.java
  preserves:
    - all tests currently green on main — annotations are compile-time; provided-scope dep doesn't affect runtime
    - all production behavior — adding @NonNull / @Nullable does not insert runtime checks
spec_refs: []
decision_refs: []
reviews: []
escalations: []
revisions: []
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
---

# M1-050: Process fix E — JSpecify parameter contracts (boundary classes + lint)

## Context

Subticket of [[M1-047]]. The API-contract complement to the existing `CLAUDE.md` §"No defensive code for impossible scenarios" rule.

The current rule prohibits paranoid null-checks but doesn't require the explicit contract that makes paranoia unnecessary. Without `@NonNull` / `@Nullable` (or `@param` javadoc), "what's legal" is implicit — a caller reading the signature can't tell whether `someService.process(x, null)` is a valid call or a bug. The complement: every reference-type parameter on a public method MUST declare nullability.

User picked JSpecify (not JetBrains) on 2026-05-21 for type-use semantics (`List<@Nullable String>`) and as the modern long-term standard, accepting the trade-off of less mature IDE tooling.

Scope this ticket conservatively: 4 boundary classes for the v1 retroactive pass (InboundRouter, MessagingAdapter SPI, CommandHandler SPI, BundleLoader). Lint enforces on these + on every NEW public method added in future diffs (via the reviewer prompt update). A follow-up ticket can widen the retroactive set to *Service classes, handler implementations, and infochat-collector boundaries.

[[M1-048]] (A) is the procedural backstop; [[M1-049]] (D) is the structural fix; this ticket is the API-design-time forcing function.

## Definition of Done

- JSpecify dep in parent pom.xml (provided scope).
- New `scripts/lint-contracts.py` script — walks `.java` files, checks public method reference-type params for @NonNull/@Nullable, supports baseline grandfathering.
- 3 docs updated: `CLAUDE.md` + `docs/process/engineering-rules-verbatim.md` (same rule text) + `docs/process/reviewer-prompt.md` (new REWORK check `PARAMETER-CONTRACT-CHECK`).
- 4 boundary files retroactively annotated: InboundRouter.java, MessagingAdapter.java, CommandHandler.java, BundleLoader.java — public methods only.
- `python3 scripts/lint-contracts.py <4 boundary files>` exits 0.
- `mvn -B clean verify` exits 0.

## Implementation notes

- **JSpecify dependency.** Add to parent pom.xml `<dependencyManagement>` so child modules can declare without re-specifying version. Each child module that uses annotations declares `<dependency><groupId>org.jspecify</groupId><artifactId>jspecify</artifactId></dependency>` (scope inherited from parent). For v1, only modules touched by the retroactive pass need the dep: `infochat-provider`, `infochat-messaging-adapter`. Add the dep to those two child POMs. (Wait — this means 2 more files. Reconsider: place the dep at parent only, with `<scope>provided</scope>` — child modules pick up the dep transitively via parent-defined `<dependencies>`. Confirm Maven semantics. If parent-only doesn't work, files_budget bump to 13 to add 2 child POMs.)
- **lint-contracts.py shape.** Plain Python 3, regex-based parser sufficient for v1 (no need for a full Java AST library). Strategy: scan for `public ` / `protected ` method declarations, extract parameter list, check each non-primitive parameter type for an annotation prefix. The primitive set is the canonical 8 + their boxed forms (Boolean, Integer, etc. count as reference and need annotation). Strings, custom classes, generics — all reference, need annotation. Print findings to stderr; exit 0 on no findings, exit 1 on any.
- **Baseline file shape.** `scripts/lint-contracts-baseline.txt` is created in this ticket's commit but starts essentially empty (just a header comment explaining the format). Future tickets populate it as the retroactive pass widens — entries get added when grandfathering known-un-annotated public methods. Format: `<path>:<method-signature>` one per line.
- **The 4 boundary files' annotation pass.** Read each file's public methods, annotate each reference-type parameter. Default policy: @NonNull unless the caller can legitimately pass null (then @Nullable). For the 4 boundary files, every parameter is non-null by contract (none of the SPI methods accept null inputs). All annotations are @NonNull.
- **Method-return annotations.** Optional in v1. The acceptance check on BundleLoader.get's return type is encouraged-not-required. Future tickets can add return-type annotations as part of the broader retroactive pass.
- **Reviewer-prompt change.** Add a new check `PARAMETER-CONTRACT-CHECK` to the reviewer's check list. The check reads the diff, identifies new/modified public methods, and REWORKs if any reference-type parameter lacks @NonNull/@Nullable. The check is per-file: a ticket that doesn't touch any boundary file doesn't trigger the check.
- **CLAUDE.md rule placement.** New subsection `### Method parameter contracts` under §Engineering rules. Place after §"No defensive code" so the rule reads as the positive complement. Same text mirrored verbatim into `docs/process/engineering-rules-verbatim.md` per the existing convention that the verbatim file is the canonical source for the reviewer.

## Big-picture notes

- **Why boundary-only for v1.** Annotating every public method across the codebase in one ticket would push files_budget to 50+ and make the diff impossibly large for review. The boundary set is the load-bearing surface — these are the contracts callers actually consult. Concrete handlers and *Service implementations either implement these interfaces (and inherit the annotated signature) or have narrow internal callers (so the contract is implicit through the trust boundary the engineering rules already define).
- **Why the lint script can be added incrementally.** The baseline-grandfather pattern means the script can be run against the whole codebase later; new un-annotated findings are baselined, and the reviewer-prompt check catches NEW un-annotated public methods at PR time. The retroactive pass shrinks the baseline over time.
- **JSpecify vs. JetBrains.** JSpecify's type-use semantics (`List<@Nullable String>`) are strictly more expressive than JetBrains' declaration-only semantics. The trade-off is less mature IDE integration today; this is acceptable for a 5-year-horizon convention choice.
- **The reviewer-prompt check fires on NEW code only.** It does NOT require backfilling existing un-annotated code in unrelated tickets — that's what the baseline file is for. A future ticket dedicated to broader retroactive coverage can run `lint-contracts.py` without baseline and incrementally annotate.

## Out-of-scope expansion

- **No retroactive annotation of *Service classes, handler implementations, or collector code.** Boundary set is intentionally narrow for v1. Future ticket widens.
- **No runtime null-checks added based on annotations.** JSpecify is compile-time/static-analysis only; production behavior does not change.
- **No JetBrains @NotNull/@Nullable.** User picked JSpecify; this ticket commits to it exclusively. If a mix were ever wanted, that's a future spec-amend.
- **No changes to existing tests.** Annotations are compile-time; existing tests stay byte-for-byte unchanged.
- **No changes to handler implementations.** AddSourceCommandHandler, SummaryCommandHandler, HelpCommandHandler implement CommandHandler — their override-method signatures inherit the annotated parent signature without their own annotation work in this ticket. (Subtle: Java requires `@Override` methods to MATCH the parent signature; the JSpecify annotation propagates without restatement at the call site. Future ticket may add explicit @Override-site annotations for clarity.)

## Authorized test changes

- (none — annotations are compile-time; no existing test is modified. lint-contracts.py's own self-test is via acceptance items 3 + 4 + 12 running it against real files.)

## Alternatives considered

- **JetBrains @NotNull/@Nullable (handoff recommendation).** Rejected by user 2026-05-21 in favor of JSpecify. See [[M1-047]] §Alternatives considered.
- **Javadoc-only convention (no annotations).** Rejected — no IDE / static-analyzer enforcement; the lint check would have to parse javadoc, which is fragile.
- **Annotate every public method across the codebase in one ticket.** Rejected — files_budget would blow past 50; review becomes unreviewable. Boundary-only + reviewer-prompt-enforces-new-code is the surgical shape.
- **Skip the lint script entirely (rely on reviewer-prompt only).** Rejected — without lint, the rule degrades to "reviewer remembers to check"; the script makes it mechanical and CI-runnable.
- **Decompose E further into E-tooling (dep + lint + docs) + E-annotation (boundary pass).** Considered; rejected because the 4 boundary files together with the tooling fit in files_budget: 11 cleanly. If the lint script turns out larger than expected, refine to widen budget rather than split.
- **Use a future-Java syntactic feature (e.g. records with null-checks).** Rejected — JSpecify works today, no Java version dependency beyond what the project already targets.
