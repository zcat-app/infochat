---
id: M1-527
title: "Command-catalogue parity test: code CommandHandler set ↔ marked index in commands.md"
status: pending
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
    A new @QuarkusTest (CommandCatalogueParityTest) injects
    Instance<CommandHandler>, collects the set of name() values, and asserts it
    equals the canonical command index parsed from docs/spec/commands.md. On a
    mismatch the test FAILS with a message naming the divergent command(s) on
    each side (in-code-but-not-indexed vs indexed-but-not-in-code).
  - >-
    The canonical index is a machine-readable, marker-delimited region in
    commands.md (between `<!-- command-index:begin -->` and
    `<!-- command-index:end -->`), one `/name` token per line, that the test
    parses EXCLUSIVELY — it never greps free prose. This is what makes the
    check false-positive-free: prose such as "there is no `/list-users` command"
    or a URL path like `/feed.xml` lives outside the marked region and cannot
    create a spurious match.
  - >-
    The index is seeded with the current static command set (the 39
    CommandHandler beans at HEAD: add-source, approve-group, audit, ban, clear,
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
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
clarity_check: {}
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

- The runtime command set is **exactly** `Instance<CommandHandler>` — verified
  39 implementors == 39 distinct `name()` literals, with **zero** pollution:
  confirm-continuation handlers (ClearConfirm, ForgetConfirm,
  UnfollowTagAllConfirm) route through ConfirmStateService and are NOT
  CommandHandler beans, and asset commands dispatch via AssetHandler. So the raw
  injected set needs no filtering.
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
    @Inject Instance<CommandHandler> handlers;

    @Test void codeCommandSetMatchesMarkedIndex() {
        Set<String> inCode = handlers.stream().map(CommandHandler::name).collect(toSet());
        Set<String> inDoc  = parseMarkedIndex(Path.of("..", "docs", "spec", "commands.md"));
        assertEquals(inDoc, inCode, () -> diffMessage(inCode, inDoc));
    }
}
```

`parseMarkedIndex` reads the file (clear failure if absent), slices between
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
