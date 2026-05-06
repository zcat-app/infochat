# 09 — Reference

Quick-lookup tables that are useful across modules: the Maven module dependency graph, and the canonical error-code catalog. Both are intentionally small; if either grows past a screen, split it out.

This file is a **reference**, not a design doc. It is normative for module dependencies (the build enforces the DAG) and for error-code allocation (`E1xxx` user, `E2xxx` LLM, `E3xxx` eval, `E4xxx` infra are reserved ranges — do NOT mint codes outside them without amending this file).

---

## 9.1 Module dependency DAG

The codebase ships as five Maven modules. Dependencies are strictly one-directional; the build fails if a cycle is introduced.

```
                    infochat-core
                   /     |        \
                  /      |         \
   infochat-llm-adapter  |    infochat-messaging-adapter
                  \      |         /
                   \     |        /
                    \    |       /
              ┌──────────┴──────────┐
              │                     │
     infochat-collector     infochat-provider
```

| Module | Depends on | Purpose |
|---|---|---|
| `infochat-core` | (none) | Domain entities, schema-level types, shared utilities. Pure Java; no Quarkus, no I/O. |
| `infochat-llm-adapter` | `infochat-core` | `LlmProvider`, `EmbeddingProvider`, `TranslationProvider` SPIs and impls. See [05-llm-and-embeddings.md](05-llm-and-embeddings.md). |
| `infochat-messaging-adapter` | `infochat-core` | `MessagingAdapter` SPI, SimpleX impl, `InMemoryAdapter`. See [06-messaging.md](06-messaging.md). |
| `infochat-collector` | `infochat-core`, `infochat-llm-adapter` | Fetchers, eval pipeline, schedulers. Headless. No `messaging-adapter` dependency — Collector never talks to users. |
| `infochat-provider` | `infochat-core`, `infochat-llm-adapter`, `infochat-messaging-adapter` | Command router, chat agent, periodic digest, admin commands. |

Notes:

- `infochat-core` MUST stay free of Quarkus, JAX-RS, and Hibernate. Test-friendly and reusable.
- `infochat-collector` MUST NOT depend on `infochat-messaging-adapter`. Enforced by the parent POM and verified in CI; an attempt to add the dependency fails the build with a clear error. This is the architectural guarantee that the Collector cannot accidentally become user-facing.
- The two adapter modules are siblings — `llm-adapter` and `messaging-adapter` MUST NOT depend on each other.
- See [F32 in the v2 review notes](../reviews-v2/claude-feedback.md) for the rationale against further splitting `infochat-core`.

---

## 9.2 Error-code catalog

Every user-visible error message and every audit-log row carries a stable error code. Codes are **range-allocated** by class:

| Range | Class | Surfaces in |
|---|---|---|
| `E1xxx` | User errors (input, permission, rate, confirmation) | Bot reply text + `audit_log.action` |
| `E2xxx` | LLM-layer errors (provider down, parse failure, capability mismatch) | `audit_log` + `llm.calls.total{outcome=fail}` metric |
| `E3xxx` | Eval-pipeline errors (Stage 1, Stage 2, tagger, embedder) | `audit_log` + `eval.*` metrics; user-facing only as degraded summaries |
| `E4xxx` | Infrastructure errors (DB, adapter, scheduler) | Logs + `adapter.*` / `provider.*` metrics; admin notifier |

Within a class, codes are not reused. Allocation is append-only — once shipped, a code's meaning is frozen.

### 9.2.1 `E1xxx` — user errors

| Code | Meaning | Example bot reply |
|---|---|---|
| `E1001` | Unknown command | `Unknown command \`/sumamry\`. Did you mean \`/summary\`?` |
| `E1002` | Unknown tag | `Unknown tag \`ai-research\`. Did you mean \`ai\`?` |
| `E1003` | Permission denied (DM scope) | `You don't have permission to run that command.` |
| `E1004` | Permission denied (group scope, requires group admin) | `Only the group admin can run that command.` |
| `E1005` | Permission denied (requires bot admin) | `Only a bot admin can run that command.` |
| `E1006` | Rate limit hit | `You're sending commands faster than the limit. Try again in a moment.` |
| `E1007` | Confirmation expired | `Confirmation expired. Re-issue the command.` |
| `E1008` | Confirmation mismatch | `That confirmation doesn't match the pending command. Re-issue.` |
| `E1009` | Invalid argument | `Argument \`-w foo\` not recognized. See \`/help <command>\`.` |
| `E1010` | Required argument missing | `\`/add-source\` requires \`--tags\` (≥1 tag).` |
| `E1011` | Banned user | `Your account has been suspended. Contact an admin.` |
| `E1012` | 1000-save cap | `Saved-post limit reached (1000). Use \`/unsave\` to make room.` |
| `E1013` | Tool-call budget exceeded | `I've hit my tool-use budget for this turn — please ask a more specific question.` |

### 9.2.2 `E2xxx` — LLM-layer errors

| Code | Meaning |
|---|---|
| `E2001` | LLM provider unreachable (network) |
| `E2002` | LLM provider returned 5xx |
| `E2003` | LLM response unparseable after 1 retry |
| `E2004` | LLM response failed schema validation (JSON mode) |
| `E2005` | LLM exceeded `max-concurrency`; queued |
| `E2006` | Capability mismatch — task asked for `SUPPORTS_LANGUAGE_CS`, no provider declared it |
| `E2007` | API key missing or rejected |
| `E2008` | Embedding dimension mismatch with active profile |

### 9.2.3 `E3xxx` — eval-pipeline errors

| Code | Meaning |
|---|---|
| `E3001` | Stage 1 regex timeout (ReDoS guard tripped) |
| `E3002` | Stage 2 verdict `INJECTION` — post quarantined |
| `E3003` | Stage 2 verdict `MALWARE` — post quarantined |
| `E3004` | Stage 2 verdict `UNKNOWN` — post quarantined |
| `E3005` | Stage 2 LLM infrastructure failure — fail-open per `infochat.security.release-on-stage2-failure` |
| `E3006` | Tagger fallback to `source.bootstrap_tags` (post.tagger_fallback=true) |
| `E3007` | Entity extractor failure — post released without entities |
| `E3008` | Embedder failure — post released without vector |
| `E3009` | Linking job: cosine threshold violation (debug only) |

### 9.2.4 `E4xxx` — infrastructure errors

| Code | Meaning |
|---|---|
| `E4001` | DB connection acquisition timeout |
| `E4002` | Flyway migration failed |
| `E4003` | Adapter WS disconnect (transient) |
| `E4004` | Adapter session token revoked → terminal `AUTH_FAILED` (see [06-messaging.md §6.4.6](06-messaging.md)) |
| `E4005` | LISTEN/NOTIFY channel dropped — reconciler runs on next startup |
| `E4006` | Outbound queue overflow — newest message dropped |
| `E4007` | Inbound queue overflow — newest message dropped + throttle reply |
| `E4008` | Scheduler missed a slot (digest worker busy) |
| `E4009` | Bootstrap-sources.json missing or malformed |
| `E4010` | Bootstrap SHA mismatch — Collector loaded a different file than Provider sees on disk |

---

## 9.3 Quick lookup: which file to read

A common task: "I see error code `Eabcd` in a log — where is it documented?"

| Symptom | Start with |
|---|---|
| Error code `E1xxx` appears in a bot reply | [03-commands.md](03-commands.md) for the command + this file §9.2.1 |
| Error code `E2xxx` in logs | [05-llm-and-embeddings.md](05-llm-and-embeddings.md) §5.8 + this file §9.2.2 |
| Error code `E3xxx` in audit | [04-security.md](04-security.md) §4.7 + this file §9.2.3 |
| Error code `E4xxx` in admin notification | [07-deployment.md](07-deployment.md) §7.14 runbook + this file §9.2.4 |
| Adapter behavior question | [06-messaging.md](06-messaging.md) |
| Schema question (table, column, index, TTL) | [02-schema.md](02-schema.md) |
| Permission question | [04-security.md](04-security.md) §4.4 |

---
