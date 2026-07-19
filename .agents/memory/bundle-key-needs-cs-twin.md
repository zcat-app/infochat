---
name: bundle-key-needs-cs-twin
description: Adding any en.properties bundle key requires a cs.properties twin or the build fails (D43 bilateral keyset).
metadata: 
  type: reference
---

Every key added to `infochat-provider/src/main/resources/bundles/en.properties` MUST also be added to `cs.properties`, even raw-string keys that have no `BundleKeys` constant. `BundleLoaderTest.everyShippedBundleHasExactlyEnKeysetMinusTheEnOnlyProbe` enforces `cs` keyset == `en` keyset minus the single deliberate `test.fallback.probe` (D43 full-keyset completeness) — it is NOT limited to BundleKeys-constant keys, so an en-only addition fails `mvn verify`.

**How to apply:** when a ticket's acceptance adds a new bundle key, include BOTH `en.properties` and `cs.properties` in `files_scope` up front (M1-528 omitted the cs twin and burned a full verify cycle before catching it). The 2-arg `bundleLoader.get(key, lang)` does fall back to en for a missing cs key, but the parity test fires before that ever matters.
