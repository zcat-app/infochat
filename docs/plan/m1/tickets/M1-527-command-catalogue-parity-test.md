---
id: M1-527
title: "Command-catalogue parity test: code CommandHandler set ↔ marked index in commands.md"
status: done
created: 2026-06-30
last_updated: 2026-06-30
blocked_by: []
files_budget: 3
files_scope:
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/CommandCatalogueParityTest.java
  - docs/spec/commands.md
complexity: medium
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - "Linting the operator/user GUIDES (README.md, SETUP_GUIDE.md, ADMIN_GUIDE.md, USER_GUIDE.md). Those are intentionally tiered prose; a name-grep over them is false-positive-prone (file paths, URLs, and negative mentions like 'there is no /list-users command' all match). Guide accuracy is covered by the periodic audit in docs/process/guide-accuracy-audit.md, NOT by this test."
  - "Behavior/flag prose accuracy (e.g. 'signal-cli is bundled', 'confirm is a two-step keyword', the backup default dir). These are semantic claims no parity test can verify; they remain audit territory."
  - "Asset-command parity against bootstrap-assets.json. Asset commands (/zcash, /monero) are dynamic and deployment-configured; covering them couples the test to the assets file. Possible future stretch, not this ticket."
  - "config-key / INFOCHAT_* env-var doc linting. Near-zero surface (the four guides cite exactly one property key and one env var, both correct) — not worth a guard."
  - "Renaming, adding, or removing any command or handler. This test documents the command surface as it IS; it never reshapes it."
acceptance:
  - >-
    A new @QuarkusTest (CommandCatalogueParityTest) enumerates the PRODUCTION
    CommandHandler beans, collects the set of name() values, and asserts it
    equals the canonical command index parsed from docs/spec/commands.md. On a
    mismatch the test FAILS with a message naming the divergent command(s) on
    each side (in-code-but-not-indexed vs indexed-but-not-in-code).
  - >-
    "Production beans" EXCLUDES test-only CommandHandler beans. A @QuarkusTest
    container also discovers @ApplicationScoped CommandHandler beans declared in
    test sources (e.g. BoomHandler in messaging/InboundRouterTest, name()="boom"),
    so a raw Instance<CommandHandler> set is polluted by them. The test resolves
    each bean's DECLARED class via BeanManager.getBeans(CommandHandler.class) ->
    Bean#getBeanClass() (the real class, not the ARC client proxy the
    @ApplicationScoped beans expose) and drops any whose CodeSource resolves under
    target/test-classes. This keeps the assertion over the real shipped command
    surface and is robust against any future test-defined handler, not just "boom".
  - >-
    The canonical index is a machine-readable, marker-delimited region in
    commands.md (between `<!-- command-index:begin -->` and
    `<!-- command-index:end -->`), one `/name` token per line, that the test
    parses EXCLUSIVELY — it never greps free prose. This is what makes the
    check false-positive-free: prose such as "there is no `/list-users` command"
    or a URL path like `/feed.xml` lives outside the marked region and cannot
    create a spurious match.
  - >-
    The index is seeded with the current static command set (the 39 PRODUCTION
    CommandHandler beans at HEAD, test-only handlers excluded per the items
    above: add-source, approve-group, audit, ban, clear,
    compress, demote, digest, export, follow-tag, forget, get-sources, get-tags,
    grant-admin, group-timezone, help, invite, lang, list-groups, list-sources,
    promote, quarantine, recover-pool, reject-group, remove-source, retry,
    revoke-admin, save, saved, source-disable, source-enable, status, stop,
    summary, unban, unfollow-source, unfollow-tag, unsave, vouch) so the test is
    GREEN at introduction. Thereafter, adding/removing/renaming a CommandHandler
    without updating the index fails `mvn verify`.
  - >-
    Asset commands (/zcash, /monero) are dynamic CommandHandler-less commands
    dispatched via AssetHandler/AssetRegistry from bootstrap-assets.json; they
    are NOT in the Instance<CommandHandler> set and are therefore explicitly
    OUTSIDE the static-parity assertion. A short note adjacent to the index in
    commands.md states this carve-out so a future reader does not add them to the
    marked region (which would red the build).
  - >-
    The test resolves commands.md relative to the surefire working directory
    (the module dir infochat-provider → `../docs/spec/commands.md`) and fails
    with a clear, actionable message if the file or the markers are absent
    (file I/O is a system boundary, so an explicit existence check here is
    correct, not defensive-code drift).
  - >-
    `mvn verify` is green: the new test passes and the full pre-existing suite
    still passes (run from the repo root; report regressions, not just the new
    green check).
test_plan:
  adds:
    - "infochat-provider/.../command/CommandCatalogueParityTest.java — asserts the runtime CommandHandler name() set equals the marked command index in commands.md; fails naming the divergent commands."
  modifies: []
  preserves:
    - all tests currently green on main
spec_refs:
  - "docs/spec/commands.md §Command catalogue"
decision_refs: []
reviews:
  - round: 1
    date: 2026-06-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 4
      added: 298
      removed: 20
escalations:
  - date: 2026-06-30
    reason: premise-fail
    reviewer_verdict_excerpt: |
      N/A — premise-fail surfaced during implementation (no reviewer round
      reached). The raw Instance<CommandHandler> set contains 40 beans, not
      the 39 the ticket asserts: a test-only @ApplicationScoped CommandHandler
      bean (BoomHandler, name()="boom", nested in
      infochat-provider/.../messaging/InboundRouterTest.java) is discovered by
      the @QuarkusTest container. mvn verify round 1: Tests run 976,
      Failures 1 (only CommandCatalogueParityTest), failure message:
      "in code but NOT indexed: [boom]". This falsifies the ticket Context
      claim "the raw injected set needs no filtering" / "zero pollution".
overrides: []
revisions:
  - date: 2026-06-30
    reason: premise-fail rework
    snapshot:
      status: escalated
      escalation_reason: premise-fail
      premise_corrected: |
        Pre-refine, the ticket asserted the raw Instance<CommandHandler> set is
        exactly the 39 production beans and that "the raw injected set needs no
        filtering" (Context) / is GREEN at introduction (acceptance item 3).
        Empirically the @QuarkusTest container ALSO discovers the test-only
        @ApplicationScoped bean BoomHandler (name="boom", nested in
        infochat-provider/.../messaging/InboundRouterTest.java), making the raw
        runtime set 40. mvn verify round 1: 976 tests, 1 failure (only this
        test): "in code but NOT indexed: [boom]". The refine corrects the
        no-filtering premise so the test excludes test-only CommandHandler
        beans, preserving the production-parity intent unchanged.
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check:
  date: 2026-06-30
  verdict: PASS
  warnings: []
  blockers: []
---

# M1-527: Command-catalogue parity test (code ↔ marked index in commands.md)

## Context

A four-file documentation audit on 2026-06-30 found that the recurring guide
drift (M1-420/421/459/469, then M1-509) splits into two classes:

1. **Name drift** — a command exists in code but is uncatalogued, or a doc names
   a command that does not exist (README's fabricated `/news`). Machine-checkable.
2. **Behavior-prose drift** — a flag, order-of-operations, or behavior is
   described wrongly (signal-cli bundled, backup dir, confirm keyword). NOT
   machine-checkable; only a reasoning audit catches it.

This ticket builds the one robust, low-false-positive guard for class (1) at the
authoritative surface — the spec command catalogue. The investigation that
scoped it established the facts that make it clean:

- The PRODUCTION command set is exactly the 39 `@ApplicationScoped`
  `CommandHandler` beans — verified 39 implementors == 39 distinct `name()`
  literals. Confirm-continuation handlers (ClearConfirm, ForgetConfirm,
  UnfollowTagAllConfirm) route through ConfirmStateService and are NOT
  CommandHandler beans, and asset commands dispatch via AssetHandler, so neither
  pollutes the production set.
- **A `@QuarkusTest` container additionally discovers test-only
  `@ApplicationScoped` `CommandHandler` beans** declared anywhere in the test
  module — `BoomHandler` (`name()="boom"`, nested in
  `messaging/InboundRouterTest`) is one. So a raw `Instance<CommandHandler>` set
  observed from inside the test is **40, not 39**, and the test MUST exclude
  test-defined handlers before comparing (resolve each bean's declared class via
  `Bean#getBeanClass()`, drop those whose `CodeSource` is under
  `target/test-classes`). The earlier "raw injected set needs no filtering"
  framing was wrong about the test runtime; the parity intent (production
  surface == marked index) is unchanged.
- A naive grep of doc text is false-positive-ridden — even over the spec,
  backtick tokens caught `/feed` (a URL path), `/source-undelete` and
  `/list-users` (both *negative* prose: "there is no … command"). The fix is to
  parse **only a marker-delimited index region**, never free prose.

Class (2) is deliberately out of scope here and is handled by the periodic guide
audit (docs/process/guide-accuracy-audit.md). The two mechanisms are
complementary: this test stops name drift at build time; the audit catches
behavior drift at release time. Neither subsumes the other.

## Why a JUnit test, not a Python lint

`scripts/lint-config-keys.py` greps Java source for `@ConfigProperty`. A command
parity check is better as a @QuarkusTest because it enumerates the **real CDI
bean set** at runtime (authoritative — no fragile `return "x"` source regex) and
rides the **existing mandatory `mvn verify` gate** with zero new CI wiring. The
engineering rules already require `mvn verify`; this guard is free inside it.

## Design sketch (implementer fills in)

```java
@QuarkusTest
class CommandCatalogueParityTest {
    @Inject BeanManager beanManager;

    @Test void codeCommandSetMatchesMarkedIndex() {
        // PRODUCTION beans only: a @QuarkusTest container also discovers
        // test-only @ApplicationScoped CommandHandler beans (e.g. BoomHandler).
        // Bean#getBeanClass() is the real declared class (not the ARC proxy);
        // drop any whose CodeSource is under target/test-classes.
        Set<String> inCode = beanManager.getBeans(CommandHandler.class).stream()
                .filter(b -> isProductionClass(b.getBeanClass()))
                .map(b -> instanceName(b))            // each bean's name()
                .collect(toSet());
        Set<String> inDoc  = parseMarkedIndex(Path.of("..", "docs", "spec", "commands.md"));
        assertEquals(inDoc, inCode, () -> diffMessage(inCode, inDoc));
    }
}
```

`isProductionClass` returns false when the bean's declared class loads from a
`target/test-classes` CodeSource. `parseMarkedIndex` reads the file (clear
failure if absent), slices between
`<!-- command-index:begin -->` / `<!-- command-index:end -->`, and collects the
`/name` token from each line. The `diffMessage` names
`inCode − inDoc` (ship-a-command-update-the-index) and `inDoc − inCode`
(catalogue-lists-a-ghost) separately.

## Notes

- **Index placement.** Add the marked region under `## Command catalogue` in
  commands.md (or as a short appendix). It doubles as a flat quick-reference of
  every command. Keep the asset-carve-out note immediately adjacent.
- **Green at birth is expected.** The catalogue is currently correct; this guard
  is regression insurance, not a current-bug fix. Its value is that the *next*
  command change can't silently skip the catalogue.
- **Do not widen to the guides.** Resist adding README/SETUP/ADMIN/USER to the
  parity set — that reintroduces the prose false-positive class this design
  exists to avoid. Guide coverage is the audit's job.

## Pre-flight self-check (author-side)

```bash
python3 scripts/lint-ticket.py docs/plan/m1/tickets/M1-527-*.md
```
