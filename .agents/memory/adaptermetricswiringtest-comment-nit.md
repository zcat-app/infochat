---
name: adaptermetricswiringtest-comment-nit
description: "AdapterMetricsWiringTest comment claims \"v1 ships no exporter extension\" — false since M1-558; fold the fix into the next ticket touching that file"
metadata: 
  type: project
---

`infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/AdapterMetricsWiringTest.java` (rejectedConsumeIncrementsInviteDropTotal, ~line 107) has a comment: "v1 ships no exporter extension, so the deployment-wide CDI registry is a childless composite whose counters are no-ops (the provider pom records this as the committed surface)." M1-558 (merged 2026-07-04) added `quarkus-micrometer-registry-prometheus`, so the composite now has a Prometheus child and the pom comment it cites was rewritten. The test still passes (it reads deltas via its own attached observer), only the comment is stale. The file was outside M1-558's files_scope, so per the surgical-changes rule the fix waits: fold the comment correction into the next ticket touching that file. Same pattern as [[evalqueueproducer-javadoc-nit]].
