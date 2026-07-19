---
name: reflection-guard-is-error-scoped-by-design
description: "InboundReflectionGuardTest covers error.* templates only — deliberately, because a syntactic census cannot judge provenance. A green guard does NOT mean reflection is impossible; don't re-litigate widening it."
metadata:
  type: project
---

The outbound-text reflection thread (friendly errors must not echo inbound
text) is discharged: the raw-token echoes were removed from the error
templates, an authorization gate that ran *after* argument reflection was moved
ahead of parsing, and `InboundReflectionGuardTest` now censuses every
`error.*`-keyed bundle-template interpolation across three forms,
auto-classifies the trivially bot-authored ones, and requires a provenance
baseline entry for the rest. A new or changed error interpolation fails the
build.

**KEY DESIGN FACT (do not re-litigate):** the guard is **error-template-scoped
BY DESIGN**, not all-sites. A syntactic census cannot judge provenance — a DB
accessor and a raw inbound token are syntactically identical — so an
all-sites frozen baseline would rubber-stamp exactly what the guard exists to
prevent. Error-scope keeps the baseline small and trustworthy and covers 6/6
historical regressions (all were error templates). Its blind spot
(`reply.*`/success templates) is DISCLOSED in the guard's own Javadoc, in
`docs/spec/security.md`, and in `docs/spec/commands.md`, so **a green guard is
not a proof that reflection is impossible**. Widening to `reply.*` or to a
taint-type is deferred future work, not an oversight.

The one known live instance in that blind spot was closed at the value source
(the display-name default) rather than by filtering the reply, so the other
echoes of the same stored value are covered too. Final rule there: strip
control characters, then **any slash at all** discards the caller-supplied
override in favour of the host-derived name, with the value NFKC-normalized
AT THE CHECK. It cost 5 review rounds and 4 redteam audits — the durable
lesson is [[handler-input-not-always-normalized]].

`docs/spec/security.md` §LLM output sanitizer correspondingly frames the
deterministic-output exemption as a **residual risk** (bot-authored, no inbound
interpolation), not as "never passes through an LLM".
Related: [[redteam-remediation-needs-reaudit]].
