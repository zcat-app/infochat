---
name: mvn-dtest-filter-blocked-by-tripwire
description: "Cross-module -Dtest/-Dit.test filtering is impossible in this repo — the parent POM hardcodes surefire failIfNoTests=true (M1-446 tripwire), which beats every CLI -D flag; targeted dev runs = module-scoped UNFILTERED `mvn -pl <module> -am verify`."
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
this is also M1-tickets' own acceptance command). Cost of not knowing:
3 failed invocations on M1-636. Full-suite round logs still go through
`scripts/verify-serialized.sh` per [[clean-verify-monitoring]].
