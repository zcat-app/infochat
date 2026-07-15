---
id: M1-630
title: "/list-sources & /get-sources: inline next-page hint on multi-page lists"
status: pending
created: 2026-07-15
last_updated: 2026-07-15
blocked_by: []
files_budget: 6
complexity: low
risk: low
round_cap: 2
security_relevant: false
migration_touch: false
out_of_scope:
  - >-
    The `page N/M` indicator itself (added by M1-625, merged) and the --page
    parse / LIMIT / OFFSET mechanics. Only the added next-page hint line changes.
  - >-
    Changing which sources a scope sees (subscription/world model) — same boundary
    as M1-625. Only the reply's navigation affordance changes.
acceptance:
  - >-
    On a listing that spans more than one page, every page EXCEPT the last carries
    an inline hint that tells the user how to reach the next page and includes the
    correct next page number (e.g. "…  /list-sources --page 2 for more"). The last
    page carries no dangling next-page hint.
  - >-
    /get-sources shows the same next-page hint behaviour as /list-sources. The exact
    command name printed in the hint is settled by the §Open-decision note below; the
    navigation target (--page <next>) is present either way.
  - >-
    A test pins both boundaries: a non-last page of a multi-page listing contains the
    next-page hint with the next page number, and the last page does not.
---

Follow-up to M1-625 (merged @65947669): M1-625 added the `page N/M` indicator to
/list-sources and its /get-sources alias, but the reply never tells the user HOW to
reach the next page — the `--page N` syntax lives only in /help. On these
user-facing list commands (unlike admin-only /audit, the precedent M1-625 matched),
a friendly listing that spans multiple pages should carry an inline hint on every
page but the last, e.g. `→ /list-sources --page 2 for the next 20`. New bundle key
needs its cs twin (D43 bilateral keyset).

Open-decision (resolve with the operator at /m1-tick start): does the hint print a
FIXED `/list-sources` token — keeping the /get-sources reply byte-identical to
M1-625's GetSourcesAliasTest.getSourcesPaginatesIdenticallyToListSourcesAbovePageLimit
equality assertion — OR the INVOKED command name, so /get-sources shows
`/get-sources --page 2` (friendlier) and that equality test relaxes to "identical
modulo the command token"? Lean: invoked name. If the invoked-name option is chosen,
updating that one M1-625 alias test is authorized by this ticket (it is the only
pre-existing test whose expectation the change touches).
