---
id: M1-704
title: "Outbound User-Agent: single-sourced, single-valued, non-rotting"
status: done
created: 2026-07-27
last_updated: 2026-07-30
blocked_by: []
files_budget: 4
files_scope:
  - infochat-ssrf/src/main/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClient.java
  - infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java
  - infochat-collector/src/main/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcher.java
  - infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherTest.java
complexity: low
risk: medium
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    Deriving the User-Agent from the Maven project version at build time
    (resource filtering, a generated constant, or a JAR-manifest read).
    Considered and rejected in Notes; this ticket removes the rotting
    version segment instead of building machinery to keep it in sync.
    Anyone who wants build-time derivation should reopen that choice
    deliberately rather than inherit it from this ticket.
  - >-
    The SSRF guard itself — scheme allowlist, IP blocklist, DNS pinning,
    redirect cap, body cap, read timeouts, and the origin-scoped
    caller-header rule (cross-origin safe set is empty). This ticket
    changes only WHICH User-Agent value the wrapper emits and stops one
    caller from appending a second one; the header-scoping policy that
    decides which headers survive a cross-origin redirect is untouched.
  - >-
    Any change to bootstrap-sources.json, the nitter/xcancel source set,
    or the D42 failure ladder. The UA once mattered to xcancel's RSS
    gate (M1-588); the live prod/runtime/bootstrap-sources.json now
    points its 28 nitter sources at nitter.net, so that coupling is not
    what this ticket is about and no source row is edited here.
  - >-
    Reddit's own API terms / UA-format guidance beyond keeping a
    descriptive product token. This ticket does not add a contact URL,
    an OAuth client id, or any other new field to the UA string.
acceptance:
  - >-
    SsrfGuardedHttpClient.USER_AGENT no longer contains a -SNAPSHOT
    qualifier or a patch-level version, and is the single definition of
    the outbound product token in main sources — a repo-wide grep for a
    second User-Agent string literal in infochat-*/src/main returns only
    this one.
  - >-
    RedditFetcher no longer declares its own USER_AGENT constant and no
    longer passes a User-Agent entry in the extraHeaders map to
    SsrfGuardedHttpClient.get; it inherits the wrapper's default. Its
    class javadoc is updated so the "Reddit blocks the default JDK
    User-Agent" rationale reads against the wrapper's default rather
    than a per-fetcher override.
  - >-
    A test in SsrfGuardedHttpClientTest pins the EXACT User-Agent value
    the wrapper emits (assertEquals against the literal), not merely its
    presence. Today the two redirect tests assert only
    assertNotNull(getFirst("User-Agent")), so the value is unpinned and
    can drift silently; after this ticket a change to the string is a
    deliberate test edit.
  - >-
    A test asserts the outbound request carries EXACTLY ONE User-Agent
    value on a Reddit-shaped fetch (headers().allValues("User-Agent")
    has size 1, or the server-side handler sees a single value). This is
    the regression guard for the duplicate-header defect described in
    Context.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-ssrf/src/test/java/app/zcat/infochat/ssrf/SsrfGuardedHttpClientTest.java
      — exact-value assertion on the emitted User-Agent, plus a
      single-value assertion proving a caller that would previously have
      overridden User-Agent no longer produces two values.
    - >-
      infochat-collector/src/test/java/app/zcat/infochat/collector/fetcher/reddit/RedditFetcherTest.java
      — a Reddit-shaped fetch carries exactly one User-Agent value.
  preserves:
    - >-
      SsrfGuardedHttpClientTest's two redirect tests
      (userAgentOnSecond assertions) — the "wrapper's own default rides
      every hop, caller headers are origin-scoped" contract must keep
      its coverage; tighten the assertions, do not retarget them onto a
      different code path.
    - >-
      RedditFetcher's existing fetch/pagination tests — the page cap,
      the listing parse, and the failure legs are unrelated to the
      header change and must stay green unmodified.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §SSRF
  - docs/design/04-security.md §4.4
decision_refs: []
reviews:
  - round: 1
    date: 2026-07-30
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 6
      added: 116
      removed: 20
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check:
  date: 2026-07-30
  verdict: PASS
  warnings:
    - >-
      Self-check note: the Context section cites
      SsrfGuardedHttpClient.java:131 for the USER_AGENT constant; the
      constant is at :133. Two-line drift only — the constant name and
      value quoted are correct, as are RedditFetcher.java:47/:75 and
      SsrfGuardedHttpClientTest.java:747/:792. Acceptance item 1's
      "only one UA literal in infochat-*/src/main" grep re-run live:
      RedditFetcher is the sole other site.
  blockers: []
escalation_reason:
---

# M1-704: Outbound User-Agent — single-sourced, single-valued, non-rotting

## Context

Two defects in how the collector's outbound `User-Agent` is composed.
Both were found while bumping the project to 1.0.0 (doc-drift audit
2026-07-27); neither is a regression, both are latent.

**1. The version segment is hardcoded and has already gone stale.**
`SsrfGuardedHttpClient.java:131` declares

```java
private static final String USER_AGENT = "infochat/1.0.0-SNAPSHOT";
```

as a literal, with no coupling to the Maven project version. The parent
pom is now `1.1.0-SNAPSHOT` and the released tag is `1.0.0`, so the
string is wrong against both. Nothing fails when it drifts: the only
tests that touch the header
(`SsrfGuardedHttpClientTest.java:747` / `:792`) assert
`assertNotNull(exchange.getRequestHeaders().getFirst("User-Agent"))` —
presence, never value. The mismatch is not new; `M1-271` recorded it
("the UA string ... vs pom `1.0.0-SNAPSHOT`") and it was not closed.
Every future release repeats it.

Advertising `-SNAPSHOT` to every host the collector fetches from is the
part worth removing regardless of the sync question: it tells external
operators the deployment is a pre-release build, which is both wrong
after a release and needless.

**2. Reddit fetches emit TWO `User-Agent` values.**
`SsrfGuardedHttpClient.get` sets its own default and then applies the
caller's map with the same builder method:

```java
    .header("User-Agent", USER_AGENT)
    .GET();
hopHeaders.forEach(reqBuilder::header);
```

`HttpRequest.Builder.header` **appends** to the value list for a name;
`setHeader` is the one that replaces. `RedditFetcher.java:47/:75`
declares its own `USER_AGENT = "infochat/1.0 (news aggregator)"` and
passes it via `Map.of("User-Agent", USER_AGENT)`, so the built request
carries both values. Verified directly rather than inferred from the
javadoc — a single-file probe reproducing the exact call shape prints:

```
User-Agent values: [infochat/1.0.0-SNAPSHOT, infochat/1.0 (news aggregator)]
count = 2
```

The existing tests cannot catch this: they read `getFirst(...)`, which
returns one value whether there are one or two.

The two defects compound. `RedditFetcher`'s javadoc says Reddit blocks
the default JDK User-Agent "so every request carries a descriptive
`User-Agent` header" — but the wrapper had already made that true for
every fetcher, so the override adds nothing except a duplicate. And the
repo now has two disagreeing product tokens (`infochat/1.0.0-SNAPSHOT`
and `infochat/1.0 (news aggregator)`), so "the collector's User-Agent"
is not one string.

## Acceptance

- `SsrfGuardedHttpClient.USER_AGENT` carries no `-SNAPSHOT` qualifier
  and no patch-level version, and is the **only** User-Agent string
  literal in `infochat-*/src/main`.
- `RedditFetcher` drops its own constant and stops passing a
  `User-Agent` entry in `extraHeaders`; its class javadoc is reworded so
  the Reddit rationale reads against the wrapper's default.
- `SsrfGuardedHttpClientTest` pins the **exact** emitted value
  (`assertEquals`), replacing the presence-only assertion as the value's
  guard.
- A test proves a Reddit-shaped fetch emits exactly one `User-Agent`
  value.
- `mvn verify` from the repo root is green.

## Out-of-scope

Build-time derivation of the version (resource filtering, generated
constant, manifest read) is rejected here — see Notes. The SSRF guard
and its origin-scoped caller-header policy are untouched: this ticket
changes which value the wrapper emits and removes one caller's
duplicate, not the rule deciding which headers survive a cross-origin
redirect. No source row, `bootstrap-sources.json` entry, or D42 ladder
behavior is edited. No new field (contact URL, client id) is added to
the UA.

## Notes

- **Why not derive from `${project.version}`.** It was the obvious first
  answer and it does not pay for itself here. `infochat-ssrf` is a plain
  library module with no Quarkus or CDI on its classpath, so there is no
  config lookup to read; a JAR-manifest `Implementation-Version` read
  returns null whenever the class runs from `target/classes` (which is
  every test and every dev-mode run), so it needs a fallback that can
  itself go stale; and Maven resource filtering means a new filtered
  resource, a static loader, and a failure mode when the resource is
  absent. All of that exists to keep a **patch-level** number accurate
  in a string where patch precision buys nothing. Dropping the rotting
  segment removes the problem instead of automating around it, which is
  what CLAUDE.md §"Simplify aggressively" asks for. Recording it here so
  a future reader sees it was weighed, not missed.

- **Suggested value, and why `1.0`.** `infochat/1.0 (news aggregator)`
  — the string `RedditFetcher` already uses, so unifying means keeping
  the more descriptive of the two rather than inventing a third. A
  stable `major.minor` token is the common crawler convention and only
  moves on a deliberate minor bump, which the new exact-value test turns
  into an explicit edit. The implementer may pick a different literal;
  the acceptance criteria constrain the *shape* (no `-SNAPSHOT`, no
  patch level, one definition), not the exact words.

- **`risk: medium`, not low.** The UA is the identity every external
  host sees, and at least one host in the source set has been observed
  gating on it: `M1-588` established that xcancel served `403` to a bare
  `curl` UA and `200` to the collector's. That specific coupling is
  **not live** — the active bootstrap sources file under `prod/runtime/`
  (the one `infochat.bootstrap.sources-file` names) points all 28
  `nitter` sources at `nitter.net`, and only the unused `-full` variant
  beside it still names `rss.xcancel.com` — but the class of risk
  stands: changing the token can change who serves
  us. The change is deliberately conservative for that reason (the new
  value stays a recognizable `infochat/...` product token, and Reddit
  keeps a descriptive UA rather than losing one).

- **Preserved control.** The two redirect tests
  (`SsrfGuardedHttpClientTest:733`, `:792`) are load-bearing beyond
  their names: they pin that the wrapper's own default rides a
  **cross-origin** hop while every caller-supplied header is dropped —
  the origin-scoped-header security property, not a UA cosmetic. Tighten
  their assertions in place; do not retarget them onto the new
  single-value test (CLAUDE.md §"Preserve the controls of a path you
  replace" — a test that incidentally pins a security property is a
  control).

- **Not a security ticket.** `security_relevant: false`: no secret,
  authorization decision, or untrusted-input path changes. The UA is
  outbound self-identification. The SSRF properties that *are* security
  controls are explicitly out of scope and unmodified.
