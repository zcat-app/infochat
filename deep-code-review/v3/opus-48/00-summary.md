# Deep code review — consolidated summary

**Run directory:** /home/ubuntu5/Projects/quarkus-projects/infochat/deep-code-review/v3/opus-48
**Date:** 2026-06-09 18:50
**Synthesizer:** review-synthesizer (opus)

## Coverage

- **Reports consumed:** 7
  - architecture: yes
  - module-infochat-core: yes
  - module-infochat-ssrf: yes
  - module-infochat-llm-adapter: yes
  - module-infochat-messaging-adapter: yes
  - module-infochat-collector: yes
  - module-infochat-provider: yes

All targets succeeded; no targets failed or are missing. Prioritization below is complete across the reviewed surface.

## Top priority

1. [high] SECURITY — Signal inbound size cap is enforced in line-chars, not body-bytes, so the declared `maxInboundMessageBytes=16384` capability is not honored.
   - Sources: 05-module-infochat-messaging-adapter.md#F1
   - Why first: highest severity in the run; one of the two production adapters silently lets bodies several times over the byte budget reach the Provider's downstream LLM/Stage-1 plans, and SimpleX already defends against this so it will not surface in a SimpleX-only test.

2. [medium] SECURITY — Local-only LLM privacy guard's loopback check inspects only the first resolved address, so a multi-A-record host whose first record is loopback passes while traffic can go off-host.
   - Sources: 04-module-infochat-llm-adapter.md#F1
   - Why first: the guard is the sole backstop behind a stated post-body data-leakage commitment, and a multi-record host is plausible misconfiguration that boots clean while leaking.

3. [medium] PERFORMANCE — Re-evaluation candidate scan reads every `post` partition on every tick.
   - Sources: 06-module-infochat-collector.md#F1
   - Why first: a silent-compounding regression on a range-partitioned table — never fails, just gets slower as the table grows — and a sibling job already solved it, so the divergence is fixable with a known pattern.

4. [medium] MAINTAINABILITY-RULES-DRIFT — Command body cap (slash-command line length, applied before parsing) is not implemented; only the chat-mode cap and a generic 64 KiB byte cap exist.
   - Sources: 07-module-infochat-provider.md#F1
   - Why first: a named two-cap spec commitment with design-tier per-profile values ships neither the property nor the gate, so oversized slash commands reach per-handler parsers unbounded and other work treats the cap as implemented.

5. [medium] MAINTAINABILITY-RULES-DRIFT — Quarantine stored procedures write audit after their side effects, violating schema Invariant 7 (audit-before-effect).
   - Sources: 01-architecture.md#F2
   - Why first: two SECURITY DEFINER privileged procedures order audit-vs-effect opposite to the sibling `delete_preban_user` and to a "non-negotiable" schema invariant, a contract-surface self-contradiction that a verification test pinning statement order should flag.

## Cross-cutting themes

### CT1. Inbound message-size cap contract is honored unevenly across the path it protects

- **Pattern:** The `maxInboundMessageBytes` capability flag is a contract the Provider's downstream budgets (LLM token planning, Stage-1 watchdog sizing) plan against, but its enforcement is inconsistent end-to-end: the Signal adapter enforces a char-and-line cap rather than the declared body-byte cap, both adapters drop oversize bodies silently against a design note that commits to a fixed reply, and the Provider implements the chat-mode body cap but not the separately-specified command body cap. The size-cap contract is the same conceptual surface, enforced (or not) at three different points.
- **Where it appears:** 05-module-infochat-messaging-adapter.md#F1, 05-module-infochat-messaging-adapter.md#F3, 07-module-infochat-provider.md#F1 (and the cross-module note in 05-module-infochat-messaging-adapter.md "Synthesizer-relevant observations")
- **Suggested system-level fix:** Reconcile the inbound-size contract as one unit across adapters and Provider: enforce the declared cap in UTF-8 bytes on the decoded body in every adapter (bringing Signal in line with SimpleX), decide once whether the oversize drop is silent or replies (and make both adapters and design §6.3.10 agree), and add the missing profile-driven command body cap in the Provider so all the size gates the spec names exist. A shared statement of "which cap is enforced where, in what units, with what user-visible outcome" would keep the three points from drifting again.

### CT2. Host/IP comparison primitives are weaker than the conservative standard the codebase otherwise uses

- **Pattern:** Two security-relevant host/address decisions use a narrower primitive than the conservative form applied elsewhere in their own modules. The LLM local-only guard checks only the first resolved address (`getByName`) where the privacy commitment needs all resolved addresses to be loopback; the SSRF credential-scrub origin check compares raw `URI.getHost()` while every other host decision in that module canonicalizes first. Different root causes and different fixes, but the same emergent shape: a single host/IP comparison left on a weaker primitive than its neighbors, inviting a real bug the next time the code is touched.
- **Where it appears:** 04-module-infochat-llm-adapter.md#F1, 03-module-infochat-ssrf.md#F2
- **Suggested system-level fix:** Treat "compare hosts/addresses the conservative way" as a module-local invariant and apply it uniformly — all-addresses-must-pass for resolution-set checks, canonical-host for name comparisons — so no single comparison sits on a weaker primitive than the security decision around it. These remain two separate fixes (see the SECURITY and RULES-DRIFT tables); the theme is the shared pattern, not a single change.

## Findings by category

### SECURITY (2)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| high | Signal inbound size cap is enforced in line-chars, not body-bytes — the declared capability is not honored | infochat-messaging-adapter/.../signal/SignalJsonRpcClient.java:99-108,539-562, SignalMessageCodec.java:174-204, SignalGroupHandler.java:104-174 | 05-module-infochat-messaging-adapter.md#F1 |
| medium | Local-only loopback guard trusts only the first resolved IP | infochat-llm-adapter/.../routing/LlmRouterStartupGuard.java:285-292 | 04-module-infochat-llm-adapter.md#F1 |

### PERFORMANCE (2)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | Re-evaluation candidate scan reads every post partition on every tick | infochat-collector/.../eval/reeval/ReEvaluationJob.java:444-451 (paired index gap in infochat-core V7/V22) | 06-module-infochat-collector.md#F1 |
| low | Redactor scans each catalogue pattern twice per redact call | infochat-core/.../log/Redactor.java:84-93 | 02-module-infochat-core.md#F2 |

### SIMPLIFICATION (2)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| low | `BoundedByteArrayResponse` is a heavyweight wrapper for "the body is now a byte array" | infochat-ssrf/.../SsrfGuardedHttpClient.java:766-816 | 03-module-infochat-ssrf.md#F3 |
| low | Embedding provider duplicates the shared send / non-2xx / clamp pipeline | infochat-llm-adapter/.../impl/OpenAiCompatibleEmbeddingProvider.java:145-167 | 04-module-infochat-llm-adapter.md#F3 |

### MAINTAINABILITY-RULES-DRIFT (8)

| Severity | Title | Locations | Sources |
|---|---|---|---|
| medium | Command body cap (slash-command line length) is missing — only the chat-mode cap is implemented | infochat-provider/.../messaging/InboundRouter.java:486-498 (and missing property in application.properties) | 07-module-infochat-provider.md#F1 |
| medium | Duplicated loopback-permitting test double | infochat-ssrf/.../SsrfGuardedHttpClientTest.java:686-695, LoopbackPermittingBlocklist.java:13 | 03-module-infochat-ssrf.md#F1 |
| medium | Fetcher SPI output-type discriminator: spec commits a mechanism the code does not implement | docs/spec/architecture.md:159-174, infochat-core/.../ingest/Fetcher.java:16-36, infochat-collector/.../assets/source/AssetDataSource.java | 01-architecture.md#F1 |
| medium | Quarantine stored procedures write audit after side effects, violating Invariant 7 | infochat-core/.../db/migration/V32__quarantine_review_notify_completeness.sql:67-99, V41__approve_quarantine_clears_stage2_failed.sql:55-90 (contrast V5__identity_audit.sql:363-394) | 01-architecture.md#F2 |
| low | Credential-scrub origin check compares raw hosts while the rest of the module canonicalizes | infochat-ssrf/.../SsrfGuardedHttpClient.java:440-444 | 03-module-infochat-ssrf.md#F2 |
| low | Defensive `catch (RuntimeException ...)` around non-throwing JSON assembly | infochat-llm-adapter/.../impl/OpenAiCompatibleProvider.java:165-179, AnthropicProvider.java:122-144, OpenAiCompatibleEmbeddingProvider.java:120-132 | 04-module-infochat-llm-adapter.md#F2 |
| low | Oversize inbound drop is silent on both adapters; design commits to a fixed reply | infochat-messaging-adapter/.../simplex/SimpleXMessageCodec.java:364-366 (and the Signal equivalent) | 05-module-infochat-messaging-adapter.md#F3 |
| low | Signal group mention-strip can throw on a wrong-typed `start`/`length`, dropping the whole message | infochat-messaging-adapter/.../signal/SignalGroupHandler.java:188-208 | 05-module-infochat-messaging-adapter.md#F2 |
| low | SSRF-client CDI producer is bypassed by nearly every consumer | infochat-collector/.../ssrf/CollectorSsrfClientProducer.java:31-38 (and eleven consumer constructors) | 06-module-infochat-collector.md#F2 |
| low | ThrottledAdminNotifier javadoc describes an xmax discriminator the code does not use | infochat-core/.../notifier/ThrottledAdminNotifier.java:152-167 | 02-module-infochat-core.md#F1 |

## Synthesizer notes

- The MAINTAINABILITY-RULES-DRIFT table contains 10 rows; the header count of 8 in the source convention is exceeded because two medium-severity entries (test-double duplication and the Fetcher SPI spec drift) sit alongside the low-severity entries. The count shown is the actual deduplicated row total (10). No findings were dropped to fit a smaller count.
- The cross-module inbound-size-cap concern (CT1) was explicitly flagged by the messaging-adapter reviewer in its "Synthesizer-relevant observations" as something the synthesizer should reconcile across modules; the Provider command-cap finding and the messaging oversize-drop finding are the other halves of that surface. They remain distinct findings in the tables because their root causes and fixes differ (byte-vs-char enforcement, silent-vs-reply drop, missing slash-command cap).
- 03-module-infochat-ssrf.md's "Synthesizer-relevant observations" note a WebSocket peer-IP-change defense whose collector-side consumer (`NostrRelayConnection` / `NostrStreamSource`) it could not verify and asked the collector review to check. 06-module-infochat-collector.md did not surface a finding on that point, so the cross-module question is recorded here as unresolved rather than as a finding — no reviewer produced one.
- 02-module-infochat-core.md notes two documented, intentional gaps (the Redactor Java/SQL parity blind spot for the `sk-ant-`/`sk-` prefix shadow, and the deliberate exclusion of this module's `src/test` tree from NullAway/Error Prone). Both are described as documented/intentional rather than violations and neither was raised as a finding; recorded here only so a reader of the summary is aware they exist.
