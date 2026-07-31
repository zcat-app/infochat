---
name: cdi-proxy-field-writes-hit-the-proxy
description: A field read/write through an injected CDI client proxy hits the proxy's own field slot, not the contextual instance's — tests must ClientProxy.unwrap; and tests that drive a scheduled onTick directly must neutralize other IT classes' standing pickup-ready rows first.
metadata:
  type: project
---

# CDI proxy field writes hit the proxy (and onTick-driven ITs must neutralize leftovers)

Two coupled traps, both hit and fixed in M1-736 (2026-07-31), each costing a
full `mvn verify` cycle to diagnose:

1. **Field access through an injected bean reference is not field access on
   the bean.** For a normal-scoped bean (`@ApplicationScoped`), the injected
   reference is a client proxy subclass: method calls delegate, but
   `bean.someField = x` and `bean.someField` hit the PROXY's own field slot —
   the contextual instance's field keeps its injected value. A test that sets
   config on the shared bean this way silently tests with the UNCHANGED value
   (M1-736: `taggerWorker.sweepBatchSize = 4` on the proxy left the real bean
   at the test-profile 0, so the feature under test never ran and produced
   zero log trace). The fix is the codebase's existing pattern:
   `ClientProxy.unwrap(bean).someField = x` (see `PriceSnapshotStoreTest`,
   `ReadyPromoterIT`, `Stage2WorkerIT`). Applies to READS too — reading a
   config int through the proxy returned 0 and silently disabled a fixture's
   attempt cap. Hand-constructed instances (`new TaggerWorker()`) have no
   proxy and are unaffected.

2. **A test that calls a scheduled `onTick()` directly inherits the whole
   shared boot's standing backlog.** Failsafe IT classes share one boot/DB
   per group; other classes leave pickup-ready rows behind (e.g.
   Stage2WorkerIT's post-stage2 rows are live-pickup-eligible for the
   tagger). A direct `onTick()` processes those first — correctly, live work
   wins — eating queued StubLlmProvider FIFO responses and inflating
   `callCount`. Fix used by `TaggerWorkerSweepIT`: per test, flip foreign
   pickup-eligible rows to `tagger_done = TRUE` (tracking their ids) and
   restore them in `@AfterEach`; squelch sweep-side eligibility with a
   max-generation UPDATE. Restoring matters: leaving the flip in place would
   make those rows visible to OTHER workers' pickups (embedding) in later
   classes.
