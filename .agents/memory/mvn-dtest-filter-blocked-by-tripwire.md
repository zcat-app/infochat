---
name: mvn-dtest-filter-blocked-by-tripwire
description: "Cross-module -Dtest filtering is impossible in this repo — the parent POM hardcodes surefire failIfNoTests=true (M1-446 tripwire), which beats every CLI -D flag. IT-only filtering IS legal: -Dit.test=FooIT -Dfailsafe.failIfNoSpecifiedTests=false and NO -Dtest (surefire must run unfiltered). Otherwise: module-scoped UNFILTERED `mvn -pl <module> -am verify`."
metadata: 
  type: project
---

`mvn -pl infochat-provider -am verify -Dtest=Foo -Dit.test=BarIT` always
fails in `infochat-core` with "No tests were executed!" — the parent
`pom.xml` (~line 234) pins `<failIfNoTests>true</failIfNoTests>` in the
surefire config as the M1-446 non-empty-unit-suite tripwire, and POM
config beats the CLI `-DfailIfNoTests=false` / `-Dsurefire.failIfNoSpecifiedTests=false`
properties.

**Why:** the tripwire exists to make a silently-skipping suite a hard
failure; per-module opt-out is a deliberate one-line visible choice, so
CLI escape hatches are (correctly) inert.

**How to apply:** for fast feedback on new tests, skip filtering and run
the whole module: `mvn -pl <module> -am verify` (~9 min for provider;
this is also M1-tickets' own acceptance command). The `-am` is not
optional — without it Maven resolves sibling modules from `~/.m2` instead
of building them, so a just-edited `messaging-adapter` silently tests as
its last-installed version. Cost of not knowing:
3 failed invocations on M1-636, 2 more on M1-788 (2026-08-07 — the entry
existed and wasn't read at session start; read the index FIRST).
Full-suite round logs still go through
`scripts/verify-serialized.sh` per [[clean-verify-monitoring]].

**Narrowing the "impossible":** the tripwire watches SUREFIRE only, so
failsafe filtering is legal — `./mvnw -B -pl <module> -am verify
-Dit.test=FooIT -Dfailsafe.failIfNoSpecifiedTests=false` runs all unit
tests but just the named IT class (the M1-784 reproduction's run command,
proven again for M1-788's RED run 2026-08-07). The moment you also pass
`-Dtest=`, core's surefire matches nothing and the tripwire fires.
