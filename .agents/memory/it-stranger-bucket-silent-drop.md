---
name: it-stranger-bucket-silent-drop
description: A provider IT that seeds users via direct SQL gets its inbound SILENTLY DROPPED late in the full suite — the M1-229 shared stranger rate bucket; fix is registeredContactSet.markRegistered() after seeding.
metadata: 
  type: project
---

An `@QuarkusTest` provider IT that seeds users with direct SQL
(`INSERT INTO users ...`) is **not** registered as far as intake step 1.5 is
concerned: `RegisteredContactSet` is an in-memory set seeded from `users` at
`@PostConstruct` only. A direct-SQL seed never reaches it, so
`InboundRouter` sees `isRegistered=false` and routes the inbound to the
**M1-229 shared per-adapter stranger bucket** (`infochat.rate-cap.inbound-per-minute=60`,
shared by ALL unregistered contacts on that adapter, deliberately NOT swept
by eviction) instead of a per-contact bucket.

**Symptom:** passes in isolation and in small class groups, fails only in the
FULL suite, and only for classes running late — by then other suites have
drained the shared 60/min bucket, so over-cap inbound is **silently dropped**
(no log, no outbound, no exception; spec §Authorization model step 1.5). A
test that drives N messages sees only some arrive. Diagnostic tell: the turn
count / `inFlightTaskCount()` is LOWER than the number of `deliverDm` calls.

**Fix (established precedent, `LanguageThreadingIT:149` has the same comment):**
```java
@Inject RegisteredContactSet registeredContactSet;
// after the INSERT:
registeredContactSet.markRegistered(ADAPTER, contactId);
```

Cost M1-635 two full red verifies (~10 min each). A wrong first hypothesis —
"foreign tasks occupy the shared `InterruptibleDispatcher` pool" — was
plausible and even produced a real hardening (a `@BeforeEach`
`inFlightTaskCount()==0` quiescence gate, worth keeping), but was NOT the
cause. What settled it: a diagnostic assert message printing
`latched/llmCalls/inFlight` — `inFlight=2` proved only 2 of 4 deliveries ever
dispatched a task, moving the search upstream of the pool to intake.

**Reusable method:** when an IT fails only in the full suite, put the counters
IN the assert message before theorizing; shared app-scoped state
(`RegisteredContactSet`, `RateCapBucket`, `InterruptibleDispatcher` pool,
breakers) is the suspect class, and the silent-drop paths hide the evidence.

Related: [[full-suite-timing-flakes]], [[clean-verify-monitoring]].
