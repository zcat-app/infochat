---
id: M1-600
title: "Provider: /grant-admin and /revoke-admin render error.probation.blocked with no MessageFormat args, so the defense-in-depth probation branch would emit literal {0}/{1} (use an arg-free probation key)"
status: pending
created: 2026-07-09
last_updated: 2026-07-09
blocked_by: []
files_budget: 7
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/GrantAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The router's step-5 probation gate (InboundRouter.java:707-718). It ALREADY
    renders error.probation.blocked correctly — MessageFormat.format(...) with
    {0}=formatTimeUntilUnlock(...) and {1}=commandPermissions
    .renderProbationCommandList() — and it is the PRIMARY, production-reachable
    probation defense that pre-gates every command before dispatch. It is not
    touched, not re-keyed, and not the site of this defect. This ticket only
    fixes the two admin handlers' own in-handler probation branches.
  - >-
    Making the existing error.probation.blocked key's {0}/{1} render work in the
    handlers by threading probation_until + a Clock + formatTimeUntilUnlock +
    CommandPermissions into GrantAdminCommandHandler / RevokeAdminCommandHandler.
    formatTimeUntilUnlock is package-private in the messaging package (not the
    command package), ProbationCheck.inProbation() returns only a boolean (no
    probation_until timestamp), and neither handler injects Clock or
    CommandPermissions. Pulling all of that across packages to enrich an
    UNREACHABLE branch is disproportionate — see §The fix / §Alternatives. Do NOT
    change ProbationCheck, InboundRouter, or CommandPermissions.
  - >-
    Removing the four defense-in-depth probation branches themselves. They are
    intentional, code-documented defense-in-depth (GrantAdminCommandHandler.java:
    284-287 "this check survives future changes that might decouple probation
    from is_admin"; the M1-045 intake gate is the primary). Deleting a documented
    security defense-in-depth is a posture change that would flip
    security_relevant and pull a redteam; it is out of scope for this
    display-string fix. This ticket keeps the branches and only makes their reply
    well-formed.
  - >-
    The other probation surfaces that already render correctly: /vouch replies,
    the /promote target-probation gate, and PendingCommandHandler's probation
    annotations. Only the two admin-command in-handler branches use the
    two-argument key with a zero-argument bundleLoader.get.
  - >-
    Any change to the probation DECISION (who is blocked, when probation clears).
    This ticket changes only the STRING rendered in an already-rejecting branch;
    the reject decision, the rollback, and the audit-on-intent row are all
    unchanged.
acceptance:
  - >-
    A new arg-free bundle key error.probation.blocked.generic is added to BOTH
    en.properties and cs.properties (D43 bilateral keyset — a cs twin is
    mandatory or BundleLoaderTest fails), carrying NO {0}/{1} placeholders. English
    value states the user is in slow-start probation and that admin commands are
    not available during probation; the cs value is the faithful translation. A
    matching constant BundleKeys.ERROR_PROBATION_BLOCKED_GENERIC =
    "error.probation.blocked.generic" is added with a Javadoc noting it is the
    argument-free reply for the admin handlers' defense-in-depth probation
    branches (the two-argument ERROR_PROBATION_BLOCKED stays for the router).
  - >-
    All FOUR call sites that today pass zero args to the two-placeholder key are
    switched to the new arg-free key, still via plain bundleLoader.get (no
    MessageFormat needed because the value has no placeholders):
    GrantAdminCommandHandler.java:226 and :290, RevokeAdminCommandHandler.java:229
    and :306. After the change, grepping ERROR_PROBATION_BLOCKED (the
    two-placeholder key) in infochat-provider/src/main/java yields exactly ONE
    non-Javadoc reference — InboundRouter's MessageFormat.format site — and no
    bundleLoader.get of a placeholder-bearing key without MessageFormat.format
    remains in either admin handler.
  - >-
    The class-Javadoc lines that name the probation reply key
    (GrantAdminCommandHandler.java:49-50 and RevokeAdminCommandHandler.java:48-49,
    currently "in probation -> error.probation_blocked") are updated to name
    error.probation.blocked.generic so the Javadoc matches the branch it
    documents. This is the only permitted edit outside the four call sites in
    those two files.
  - >-
    NAMED TEST in GrantAdminCommandHandlerTest: a new
    grantAdminInProbationEmitsGenericProbationReplyWithoutLiteralPlaceholders (or
    equivalently named) case seeds an actor that is is_admin=TRUE AND in probation
    (probation_until in the future, via the class's existing seed helpers +
    QuarkusMock-pinned Clock if the seed needs a fixed now), invokes handler
    .handle() directly, and asserts the reply text EQUALS bundleLoader.get(
    ERROR_PROBATION_BLOCKED_GENERIC, ...) and CONTAINS NEITHER "{0}" NOR "{1}".
    Red-before / green-after: on main the branch renders the raw two-placeholder
    value, so the "no literal {0}/{1}" assertion fails before the fix and passes
    after.
  - >-
    NAMED TEST in RevokeAdminCommandHandlerTest: the mirror
    revokeAdminInProbationEmitsGenericProbationReplyWithoutLiteralPlaceholders
    case, same shape (admin-in-probation actor, direct handle(), assert generic
    reply, no literal braces). Both handlers reach the branch via the in-handler
    probationCheck.inProbation gate, which the direct handler call exercises even
    though the production router pre-gates it.
  - >-
    mvn verify is green from the repo root (full pre-existing suite, not just the
    two new cases), with no regression in BundleLoaderTest (the new key is present
    in both bundles) or the existing Grant/RevokeAdminCommandHandlerTest scenarios
    (a)..(f).
test_plan:
  adds: []
  modifies:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/GrantAdminCommandHandlerTest.java
      — add the admin-in-probation case asserting the generic reply and the
      absence of literal {0}/{1}. Reuse the class's existing user-seed +
      audit-trigger-disable cleanup harness; seed probation_until in the future so
      probationCheck.inProbation returns true for an is_admin row.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/RevokeAdminCommandHandlerTest.java
      — add the mirror admin-in-probation case, same assertions, same seed
      pattern.
  preserves:
    - all tests currently green on main
    - >-
      the existing Grant/RevokeAdminCommandHandlerTest acceptance scenarios
      (a)..(f) — DM-only, non-admin, missing-arg, unknown/banned/already-admin (or
      not-admin), success, last-admin — none of which assert on the probation
      branch.
    - >-
      BundleLoaderTest's D43 bilateral-keyset check — satisfied because the new
      key is added to both en and cs.
spec_refs:
  - docs/spec/security.md §Slow-start tier
  - docs/spec/commands.md §Admin (bot admin)
decision_refs:
  - D43
  - D45
reviews: []
escalations: []
overrides: []
revisions: []
aborted_attempts: []
reopens: []
redteam_findings: []
redteam_audits: []
---

# M1-600: /grant-admin and /revoke-admin would emit literal {0}/{1} in the probation branch

## Context

Follow-up owed from **M1-590** (canonical probation command list, merged
`b1debea7`) and flagged in the live-test-fixes progress notes as "out of scope,
NOT filed". Filed now.

`GrantAdminCommandHandler` and `RevokeAdminCommandHandler` each have two
in-handler probation guards (a pre-transaction check and an in-transaction
defense-in-depth re-check). All four render the probation rejection like this:

```java
return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED,
        inboundContext.effectiveLanguage()));
```

But the `error.probation.blocked` value carries two `MessageFormat` placeholders:

```
error.probation.blocked=You are still in slow-start probation. Full access unlocks in {0}. Allowed during probation: {1}.
```

Because the handlers call `bundleLoader.get(...)` with **no** `MessageFormat.format`
wrapper and **no** arguments, if such a branch were ever reached the user would
see the raw string with literal `{0}` and `{1}` braces. The router does it
correctly (`InboundRouter.java:707-718` wraps the same key in
`MessageFormat.format(...)` with `formatTimeUntilUnlock(...)` and
`renderProbationCommandList()`); the two admin handlers do not.

**Provenance of each placeholder.** `{0}` (the time token) is pre-existing — the
key has always had it. `{1}` (the command-list token) was added by **M1-590**,
which widened the value from one placeholder to two. So M1-590 doubled the
already-latent literal-brace exposure in these branches without touching their
zero-argument render sites.

### Reachability — why this is a latent, not an active, bug

These four branches are **defense-in-depth and unreachable in production**: the
router's step-5 probation gate (`InboundRouter.java:707`) rejects any probation
user invoking a non-allowed command *before* command dispatch, and `/grant-admin`
/`/revoke-admin` are admin-only (never in `allowedDuringProbation`). To reach the
handler branch a caller would have to be **both `is_admin=TRUE` and in probation**
— a state the current registration flow does not produce. The code comments say
so explicitly: `GrantAdminCommandHandler.java:284-287` — "this check survives
future changes that might decouple probation from is_admin". So the fix is a
correctness/quality hardening of a dead-but-documented branch, not a live-bug
patch. It IS directly testable, because the handler unit tests invoke `handle()`
directly (bypassing the router) and can seed an admin-in-probation row.

## The fix

Add an **argument-free** probation key and use it at the four handler sites:

```properties
# en.properties
error.probation.blocked.generic=You are still in slow-start probation. Admin commands are not available during probation.
# cs.properties (D43 bilateral twin — mandatory)
error.probation.blocked.generic=Stále jste ve zkušebním období. Příkazy pro správce nejsou během zkušebního období dostupné.
```

```java
// all four sites, still a plain get (no placeholders => no MessageFormat)
return reply(scope, bundleLoader.get(BundleKeys.ERROR_PROBATION_BLOCKED_GENERIC,
        inboundContext.effectiveLanguage()));
```

The two-placeholder `error.probation.blocked` key **stays** — it is the router's
hot-path reply and must keep the `{0}` unlock-time / `{1}` allowed-list detail. A
separate arg-free key for the admin handlers keeps each render site honest: the
router has the data to fill both tokens; the admin handlers (which would fire only
in the hypothetical decoupled-probation future) do not, and a generic
"admin commands are not available during probation" reply is the truthful thing to
say there.

## Alternatives considered

- **Render the existing two-placeholder key with real args in the handlers.**
  Rejected as disproportionate: `formatTimeUntilUnlock` is package-private in the
  `messaging` package (handlers live in `command`), `ProbationCheck.inProbation()`
  returns only a boolean (no `probation_until`), and neither handler injects
  `Clock` or `CommandPermissions`. Matching the router's render would mean pulling
  four dependencies across a package boundary to enrich an unreachable branch.
- **Delete the four defense-in-depth branches** (per the "No defensive code for
  impossible scenarios" rule). Rejected here: they are *pre-existing* and
  *code-documented* security defense-in-depth against a future probation/is_admin
  decoupling; removing them is a security-posture change that would flip
  `security_relevant` and pull a redteam. Out of scope for a display-string fix.
  If the project later decides these branches are genuinely unwanted, that is a
  separate, security-reviewed ticket.

## Out-of-scope

See frontmatter. Notably: the router's correct render site, `ProbationCheck` /
`CommandPermissions` / `InboundRouter`, the branch-removal option, and the
probation *decision* logic (only the rendered string changes).

## Notes

- **Why file it at all if it's unreachable.** A latent literal-`{0}`/`{1}` in a
  user-facing reply is a correctness debt with a one-key fix; leaving it means the
  documented "decouple probation from is_admin" future would silently ship broken
  copy. The cost to close it now is minimal and bounded.
- **D43 / bundle twin.** Adding an `en.properties` key without its `cs.properties`
  twin fails `BundleLoaderTest`'s bilateral-keyset check; both files are in
  `files_scope` for that reason.
- **Testability.** The branch is unreachable through the router but reachable by
  calling `handle()` directly with an `is_admin` + in-probation seed — exactly
  what the `@QuarkusTest` handler tests already do (direct handler invocation
  against DevServices Postgres). Both new cases assert the reply contains no
  literal `{0}`/`{1}`, which is red on main and green after the key swap.
