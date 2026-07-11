---
id: M1-603
title: "One LLM service by default: shared infochat.llm.default.{base-url,api-key} inherited by every ModelTask; remove the per-task baked base-urls so a task absent from operator config inherits the operator's endpoint — or refuses boot loudly"
status: done
created: 2026-07-10
last_updated: 2026-07-11
blocked_by: []
files_budget: 20
files_scope:
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/impl/AnthropicProvider.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouter.java
  - infochat-llm-adapter/src/main/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard.java
  - infochat-collector/src/main/resources/application.properties
  - infochat-provider/src/main/resources/application.properties
  - prod/scripts/4-llm.sh
  - prod/switch-llm.sh
  - prod/scripts/restore.sh
  - infochat-provider/src/test/java/app/zcat/infochat/provider/wiring/RestoreWiringTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/OpenAiCompatibleProviderTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/impl/AnthropicProviderTest.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/routing/LlmRouterStartupGuard*Test.java
  - infochat-llm-adapter/src/test/java/app/zcat/infochat/llm/wiring/*WiringTest.java
  - docs/spec/llm.md
  - docs/spec/decisions.md
  - docs/design/05-llm-and-embeddings.md
complexity: high
risk: medium
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    Embeddings. infochat.embeddings.base-url is a separate SPI surface with its
    own resolution (one provider per deployment, D54: embeddings NEVER route
    remote) and is NOT swept into the new shared default. The embedder is not a
    ModelTask; its key, baked default, and wizard lines stay exactly as they
    are.
  - >-
    Per-task MODEL keys. infochat.llm.<task>.model stays per-task with baked
    per-task defaults (profiles tune models per task — e.g. vps reuses one model
    for security+chat, pi runs smaller models). Model is task tuning, not
    service identity; it does not inherit from a default.model key and no such
    key is introduced. A stale operator config whose NEW task inherits the
    operator's remote default.base-url while its baked model is a local-runtime
    name (llama3.1:8b) is caught by the EXISTING M1-577 mismatch scan
    (local-runtime model prefix + remote host → WARN / opt-in fail-fast) — that
    scan and its localOllamaShapeIsNotFlagged carve-out are unchanged.
  - >-
    The per-task base-url/provider OVERRIDE keys themselves as an axis.
    Per-task overrides remain fully supported (highest precedence), and old
    wizard-generated operator files that fan the endpoint out per-task remain
    valid. This ticket changes what a task with NO per-task value falls back to;
    it does not deprecate the per-task axis (docs/spec/llm.md Goal 2). NOTE
    (budget-breach refine, 2026-07-11): the BAKED %remote-llm per-task Anthropic
    route blocks are NO LONGER protected by this entry — acceptance item 3
    removes them, because once the wizard stops writing plain per-task
    base-urls (today those win by ordinal, 260 over 250), the baked profile
    lines would resolve as per-task values and shadow the operator's default,
    routing chat/summarizer/translator to api.anthropic.com with a
    non-Anthropic key — a 401 on every call, invisible to the M1-577 scan
    (anthropic + anthropic.com is a consistent triple).
  - >-
    Reconciling the "ingest tasks stay on the local model even under the
    remote-llm profile" comment (baked classifier block) with the D54 wizard
    behavior that routes ALL generative tasks to the remote endpoint — a
    checked-in design contradiction surfaced during this re-scope (2026-07-11).
    This ticket preserves the operative behavior (per-task %remote-llm
    overrides stay; wizard-generated configs win at ordinal 260) and the
    contradiction is left for a spec follow-up. Also out of scope: making
    docker-compose.yml inject a compose-correct default base-url
    (http://ollama:11434/v1) — compose environment entries sit at ordinal 300,
    ABOVE the operator's mounted file (260), so they would silently override
    operator intent; no clean static answer exists and it is not attempted
    here.
  - >-
    The operator runtime config on any host (prod/runtime/application.properties)
    and the already-completed 2026-07-10 corpus reclassification — host-local
    operator actions, gitignored, not repo artifacts. Startup-guard detection
    approaches (config-source ordinal introspection, reachability probing,
    loopback-outlier heuristics) considered in earlier drafts of this ticket are
    all superseded: this ticket removes the misconfiguration CLASS instead of
    detecting instances of it.
acceptance:
  - >-
    Two new shared config keys exist: infochat.llm.default.base-url and
    infochat.llm.default.api-key, declared as constants next to the existing
    LlmRouter.CONFIG_KEY_DEFAULT_PROVIDER (same naming family,
    infochat.llm.default.*). Both concrete providers (OpenAiCompatibleProvider,
    AnthropicProvider) resolve a task's effective base-url as: per-task
    infochat.llm.<task>.base-url when set, else infochat.llm.default.base-url.
    The effective api-key is the per-task infochat.llm.<task>.api-key when
    set; else — ONLY when the task's base-url ALSO resolved from the shared
    default — infochat.llm.default.api-key; else empty string. The COUPLING is
    the security property (redteam 2026-07-11, INFO-LEAK medium): the default
    credential travels only to the default endpoint, so a task whose base-url
    is pinned per-task never receives the deployment-wide key implicitly — an
    operator pinning a task to another route on the same provider restates
    the api-key explicitly (one line). The existing per-call validation
    (LlmHttpSupport.requireHttpBaseUrl) runs against the effective value.
  - >-
    A task with NO effective base-url (neither per-task nor default) refuses
    startup through the existing assertAllTasksResolve /
    assertTaskConfigResolvable path, with an error message naming BOTH keys the
    operator can set (the per-task key and infochat.llm.default.base-url) — a
    loud boot failure instead of the 2026-07-10 silent 100%-call-failure. No
    new guard scan is added for this; the required-config machinery already
    fails boot, it just gains the default-key fallback and the two-key message.
  - >-
    The baked per-task base-url and api-key lines (7 tasks × 2 keys, in BOTH
    infochat-collector and infochat-provider application.properties) are
    REMOVED and replaced by profile-scoped shared defaults:
    %laptop/%vps/%pi.infochat.llm.default.base-url=http://localhost:11434/v1
    (their Ollama is genuinely on-host), and NO default for %remote-llm — on
    that profile every task must be routed explicitly (per-task key or an
    operator-set default), so a stale operator config that predates a NEW
    ModelTask fails boot naming the missing keys instead of silently inheriting
    a dead loopback address. The baked %remote-llm per-task Anthropic route
    blocks (chat/summarizer/translator base-url/provider/model/max-tokens, plus
    %remote-llm.infochat.llm.anthropic.languages, in BOTH files) are REMOVED:
    they carry no api-key (401 out of the box) and post-change they would
    shadow the operator's default endpoint (per-task beats default), so
    %remote-llm becomes uniformly "route everything explicitly". Quarkus
    test/dev launch modes
    keep booting: the test/dev profiles get an explicit default.base-url so
    every existing @QuarkusTest and quarkus:dev boot still resolves (concrete
    mechanism — %test./%dev. lines or equivalent — is the implementer's
    choice).
  - >-
    LlmRouterStartupGuard consumes EFFECTIVE per-task base-urls (per-task key,
    else the new default key) in all three existing scans: the local-only
    conflict scan treats an off-host infochat.llm.default.base-url as an
    offender for every task without a per-task override (mirroring how it
    already treats a cloud-only default.provider), the non-local-only
    disclosure WARN discloses tasks made remote via the default, and the
    M1-577 mismatch scan judges effective (provider, base-url, model) triples.
    A FOURTH, advisory scan (redteam re-audit 2026-07-11, INFO-LEAK low) WARNs
    when a task has a per-task api-key but NO per-task base-url — that per-task
    credential would ride the shared default endpoint, a party it may not be
    minted for (the mirror of the coupling rule; the shape a partial manual
    unpin leaves). It fires only when a shared default base-url exists and is
    advisory (never fatal), because the same shape is the legitimate
    separate-credential-for-the-default-endpoint config the operator alone can
    disambiguate. snapshotConfig snapshots the default base-url and per-task
    api-key PRESENCE (a non-secret marker, NEVER the raw value); the raw
    default api-key is NOT snapshotted (no scan reads it — re-audit
    out-of-model #3, no secret held in the map the WARN/fatal paths iterate).
    Every existing verdict on configs that set per-task keys explicitly is
    unchanged (the existing guard tests stay green, with only additive new
    cases).
  - >-
    The wizard writes the shared default instead of fanning out per task:
    prod/scripts/4-llm.sh (set_all_base_urls / set_llm_base_urls and the api-key
    loops) and prod/switch-llm.sh write infochat.llm.default.base-url +
    infochat.llm.default.api-key once, keep writing per-task model lines, and
    leave infochat.embeddings.* handling unchanged. The wiring tests
    (SwitchLlmWiringTest, RemoteLlmWiringTest, LlamacppWiringTest) are updated
    to assert the new written keys (note: switch-llm's positional stdin
    consumption changes when the per-task loop collapses — see the M1-599
    lesson).
  - >-
    prod/scripts/restore.sh rehydrate_models() classifies the generative
    backend from the EFFECTIVE endpoint: it reads
    infochat.llm.default.base-url first and falls back to
    infochat.llm.chat.base-url when the default key is absent, so BOTH
    new-format (default-key) and old-format (per-task fan-out) restored
    configs classify correctly — an ollama-backend restore keeps re-pulling
    its models instead of misclassifying as 'remote'. RestoreWiringTest
    covers the new-format classification (budget-breach refine, 2026-07-11).
  - >-
    New tests: (a) provider-level — a task with only the default keys set
    resolves to the default endpoint/key, a task with both set resolves to the
    per-task value, a task with neither fails with the two-key message; (b)
    guard-level — off-host default.base-url under local-only is fatal and names
    the default key, the M1-597 incident shape (operator default =
    remote endpoint, task with no per-task keys) resolves to the remote
    endpoint rather than any loopback fallback. docs/spec/llm.md §Per-task
    routing rules documents the base-url/api-key resolution order,
    docs/design/05-llm-and-embeddings.md documents the key table change, and
    docs/spec/decisions.md gains a decision entry (next free D-number): "one
    LLM service by default — per-task base-url/api-key inherit from
    infochat.llm.default.*; per-task overrides remain; %remote-llm has no
    baked default so unrouted tasks refuse boot." mvn verify green from the
    repo root.
test_plan:
  adds:
    - >-
      OpenAiCompatibleProviderTest + AnthropicProviderTest: default-only
      inheritance, per-task-beats-default precedence, neither-set → config
      error naming both keys (per-task + default), and the coupled-axes rule
      (redteam 2026-07-11): a per-task base-url pin with NO per-task api-key
      sends no credential at all (the auth header is absent) even when
      infochat.llm.default.api-key is set.
    - >-
      LlmRouterStartupGuardTest: local-only + off-host default.base-url →
      fatal naming infochat.llm.default.base-url; incident shape (remote
      default, no per-task keys for one task) → that task's effective route is
      the remote endpoint (asserted via the mismatch-scan/disclosure surfaces);
      mismatch scan judges effective triples (local-runtime model + remote
      DEFAULT base-url → flagged); the orphan-api-key detector (redteam
      re-audit 2026-07-11) — a per-task api-key with no per-task base-url and a
      shared default base-url set → flags exactly that task naming both keys;
      no false positive when the per-task base-url is also set, or when no
      shared default base-url exists.
  modifies:
    - >-
      SwitchLlmWiringTest / RemoteLlmWiringTest / LlamacppWiringTest — assert
      infochat.llm.default.base-url + default.api-key written once instead of
      seven per-task lines; positional stdin expectations updated where the
      per-task prompt loop collapsed.
    - >-
      Existing provider/guard tests that seeded per-task base-urls keep
      passing unchanged (per-task keys keep highest precedence).
  preserves:
    - >-
      Every existing guard verdict for explicitly per-task-configured
      snapshots; the M1-577 scan and its localOllamaShapeIsNotFlagged
      carve-out; embeddings resolution (D54) untouched; old wizard-format
      operator files (per-task fan-out) remain fully valid; restore.sh keeps
      classifying old-format dumps correctly (chat.base-url fallback).
    - >-
      The eval workers' graceful {unknown} fallback and all runtime routing
      priorities (per-task override → language capability → default provider)
      — only the config-value fallback for base-url/api-key changes.
spec_refs:
  - docs/spec/llm.md §Per-task routing rules
  - docs/spec/llm.md §Goals
decision_refs:
  - D54
redteam_findings:
  - date: 2026-07-11
    category: INFO-LEAK
    severity: low
    resolved: 2026-07-11 in-branch (advisory orphan-key WARN + presence-only snapshot; r3 audit CLEAN)
    promise: |
      security.md §Threat model: remote LLM endpoints are untrusted parties;
      §Secrets handling: the LLM API key's exposure surfaces are enumerated
      and controlled. D56 (this ticket): the default credential travels only
      to the default endpoint.
    gap: |
      Re-audit (r2): the coupled-axes remediation covers one direction only.
      A task with a per-task api-key but NO per-task base-url silently falls
      back to the shared default endpoint and transmits the PER-TASK
      credential there — a party that key was not minted for when the key
      belongs to a previously pinned foreign provider. Pre-D56 the orphan
      shape resolved to baked loopback; the diff's silent base-url fallback
      is what lets the stale key travel. No guard scan or doc covers the
      orphan; requires a partial manual unpin (no shipped tooling produces
      it — hence low, not medium).
    repro: |
      Pin chat to provider B with both lines (per the diff's advice); later
      unpin by deleting only the base-url line. Boot is clean; the first chat
      call sends provider B's key to provider A's server.
    suggested_fix_class: trust-boundary-tightening
  - date: 2026-07-11
    category: INFO-LEAK
    severity: medium
    resolved: 2026-07-11 in-branch (coupled-axes remediation; re-audit r2 confirms closed)
    promise: |
      security.md §Threat model: LLM endpoints (local or remote) are untrusted
      black boxes; §Secrets handling treats the LLM API key as a secret whose
      exposure surfaces are enumerated and controlled.
    gap: |
      The shared api-key fallback resolves INDEPENDENTLY of the base-url axis:
      a task with a per-task base-url OVERRIDE but no per-task api-key inherits
      infochat.llm.default.api-key and transmits it (Authorization: Bearer /
      x-api-key) to the OVERRIDDEN endpoint — a party the key was not minted
      for. New in this diff (pre-D56, an unset per-task key meant empty). The
      diff's own prose (LlmRouter javadoc "override one axis per task",
      4-llm.sh / switch-llm.sh pin-one-task-by-hand advice) recommends exactly
      the triggering shape; no guard scan or disclosure WARN covers the
      credential's destination.
    repro: |
      1) Wizard remote run: default.base-url=https://provider-a/v1 +
         default.api-key=$${INFOCHAT_LLM_API_KEY} (provider A's key).
      2) Operator pins one task per the printed advice:
         infochat.llm.chat.base-url=https://provider-b/v1 (no per-task key).
      3) Boot clean; first chat call sends provider A's credential to
         provider B (or to the unauthenticated local Ollama if pinned local).
         No startup signal names the credential's new destination.
    suggested_fix_class: trust-boundary-tightening
redteam_audits:
  - date: 2026-07-11
    verdict: CLEAN
    base: "merge-base(main, m1/M1-603-shared-llm-default-endpoint) = 1be537db"
    head: "working tree of m1/M1-603-shared-llm-default-endpoint (post-r2-remediation, pre-commit)"
    verdict_file: docs/plan/m1/redteam/M1-603-2026-07-11-r3.md
    out_of_model_count: 3
    note: |
      Third audit: CLEAN. Both prior findings confirmed closed (r1 medium via
      coupled axes; r2 low via the advisory orphan-key WARN + presence-only
      snapshot). Out-of-model items carried as advisory only.
  - date: 2026-07-11
    verdict: FINDINGS
    base: "merge-base(main, m1/M1-603-shared-llm-default-endpoint) = 1be537db"
    head: "working tree of m1/M1-603-shared-llm-default-endpoint (post-remediation re-audit, pre-commit)"
    verdict_file: docs/plan/m1/redteam/M1-603-2026-07-11-r2.md
    findings_count: 1
    out_of_model_count: 3
    note: |
      Re-audit after the coupled-axes remediation: the round-1 medium is
      CLOSED. New low — orphan per-task api-key (no per-task base-url)
      rides the silent base-url fallback to the default endpoint. Requires
      a partial manual unpin; no shipped tooling produces the shape.
      Out-of-model: switch-llm mixed-file consent regression (carried),
      DNS TOCTOU (pre-existing, out of scope), unused raw default.api-key
      value held in the guard's snapshot map (inert hazard-class note).
  - date: 2026-07-11
    verdict: FINDINGS
    base: "merge-base(main, m1/M1-603-shared-llm-default-endpoint) = 1be537db"
    head: "working tree of m1/M1-603-shared-llm-default-endpoint (pre-commit audit)"
    verdict_file: docs/plan/m1/redteam/M1-603-2026-07-11.md
    findings_count: 1
    out_of_model_count: 2
    note: |
      Pre-commit gate audit (run.md step 5). One medium INFO-LEAK: the shared
      default api-key follows a per-task base-url pin to a foreign endpoint.
      Halted before commit; resolution via the redteam-finding escalation.
      Out-of-model: switch-llm mixed-file all-Enter now mutates (consent
      regression, user decides); boot-time DNS TOCTOU on the default key
      (pre-existing mechanism, operator DNS out of threat-model scope).
reviews:
  - round: 3
    date: 2026-07-11
    verdict: APPROVE
    checks:
      scope_drift: PASS
      test_integrity: PASS
      out_of_scope: PASS
      negative_space: PASS
      acceptance: PASS
    diff_stats:
      files: 24
      added: 1691
      removed: 434
escalations:
  - date: 2026-07-11
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Red-team RE-audit (docs/plan/m1/redteam/M1-603-2026-07-11-r2.md), after
      the coupled-axes remediation closed the round-1 medium: INFO-LEAK low —
      the coupling was fixed one direction only. The MIRROR shape is open: a
      task with a per-task api-key but NO per-task base-url silently falls back
      to the shared default endpoint and transmits the PER-TASK credential
      there (a party it was not minted for, when the orphaned key belongs to a
      previously pinned foreign provider). Requires a partial manual unpin (no
      shipped tooling produces it → low). Resolution (user-approved, option A):
      refine — add an advisory guard WARN on the orphan shape, and drop the
      unused raw default.api-key from the guard snapshot (fold in re-audit
      out-of-model #3).
  - date: 2026-07-11
    reason: redteam-finding
    reviewer_verdict_excerpt: |
      Red-team pre-commit audit (docs/plan/m1/redteam/M1-603-2026-07-11.md):
      INFO-LEAK medium — the shared api-key fallback resolves INDEPENDENTLY
      of the base-url axis, so a task with a per-task base-url OVERRIDE but
      no per-task api-key inherits infochat.llm.default.api-key and
      transmits the deployment-wide credential to the overridden endpoint
      (a party the key was not minted for; or the unauthenticated local
      Ollama). The diff's own operator prose recommends exactly the
      triggering shape. Resolution (user-approved): refine — couple the
      axes (default api-key inherited ONLY when the base-url also resolved
      from the default).
  - date: 2026-07-11
    reason: budget-breach
    reviewer_verdict_excerpt: |
      N/A — pre-implementation, from the plan-writer outline's verified risks:
      (1) prod/scripts/restore.sh rehydrate_models() (lines 606-624) classifies
      the generative backend from infochat.llm.chat.base-url, which a new-format
      config no longer carries → an ollama-backend restore misclassifies as
      'remote' and never re-pulls models. restore.sh + RestoreWiringTest are
      outside files_scope and files_budget is 18/18 with zero headroom.
      (2) The %remote-llm baked per-task Anthropic lines (both props files, no
      api-key) currently LOSE to the wizard's plain per-task lines (ordinal
      260 > 250; verified live — chat works on DeepSeek). Once the wizard writes
      only default keys, per-task-beats-default resolution routes
      chat/summarizer/translator to api.anthropic.com with the operator's
      non-Anthropic key → 401 every call, invisible to the M1-577 scan
      (consistent triple). The clean fix (remove those baked lines) is forbidden
      by out_of_scope entry 3 ("stay byte-for-byte").
overrides: []
revisions:
  - date: 2026-07-11
    reason: >-
      redteam-finding refine r2 (user-approved option A, post-round-2-APPROVE):
      the re-audit found the mirror of the closed medium — an orphan per-task
      api-key (no per-task base-url) rides the silent base-url fallback to the
      shared default endpoint carrying the per-task credential (INFO-LEAK low).
      Acceptance item 4 gains (a) an ADVISORY guard WARN naming a task with a
      per-task api-key but no per-task base-url (fires only when a shared
      default base-url exists; advisory because the same shape is the
      legitimate separate-key-for-the-default-endpoint config), and (b) the
      snapshot change: the raw default api-key is DROPPED from the guard
      snapshot (no scan read it — re-audit out-of-model #3) and per-task
      api-key PRESENCE (a non-secret marker, never the raw value) is added for
      the orphan scan. test_plan.adds gains the orphan-detector case.
  - date: 2026-07-11
    reason: >-
      redteam-finding refine (user-approved, post-round-1-APPROVE, in-branch
      remediation): the pre-commit red-team audit found a medium INFO-LEAK —
      api-key inheritance decoupled from base-url inheritance sends the
      deployment-wide credential to a per-task-pinned endpoint it was not
      minted for. Acceptance item 1's api-key clause amended from "per-task,
      else default, else empty" to the COUPLED rule: the default api-key is
      inherited ONLY when the task's base-url also resolved from the shared
      default; a per-task base-url pin without a per-task api-key gets ""
      (explicit key required). test_plan.adds gains the no-implicit-credential
      case; the LlmRouter default-api-key javadoc, wizard pin-by-hand prose,
      spec/design/D56 wording follow.
  - date: 2026-07-11
    reason: >-
      budget-breach refine (user-approved, pre-implementation): the plan-writer
      outline surfaced two verified premise gaps. (1) prod/scripts/restore.sh
      rehydrate_models() infers the generative backend from
      infochat.llm.chat.base-url, which a new-format config no longer carries —
      an ollama-backend restore would classify as 'remote' and never re-pull
      its models; restore.sh + RestoreWiringTest added to files_scope,
      files_budget 18→20, new acceptance item for the default-first fallback
      read. (2) The baked %remote-llm Anthropic route blocks (no api-key)
      would shadow the operator's default once the wizard stops writing plain
      per-task lines (per-task beats default in the new resolution; today the
      wizard's plain lines win by ordinal 260>250) → 401 on chat/summarizer/
      translator, invisible to the M1-577 scan; out_of_scope entry 3's
      "stay byte-for-byte" protection replaced with their removal (incl.
      %remote-llm.infochat.llm.anthropic.languages), folded into acceptance 3.
aborted_attempts: []
reopens: []
clarity_check:
  date: 2026-07-11
  verdict: PASS
  warnings: []
outline_file: target/m1-tick-outline-M1-603.md
---

# M1-603: one LLM service by default — shared endpoint config every task inherits

## The problem, in plain English

The app runs seven LLM "tasks" (security judge, tagger, entity extractor,
classifier, summarizer, chat, translator). Today each task carries its **own**
server address (`infochat.llm.<task>.base-url`) and API key. There is no shared
"here is my LLM service" setting — even though in practice every deployment
points all seven tasks at **one** service (the wizard literally writes the same
URL seven times: `4-llm.sh` — "The seven per-task LLM config families share one
endpoint; only the model differs").

Because the address is stored per-task, each task also has its own **baked-in
fallback** (`http://localhost:11434/v1`, 7× in both the collector's and the
provider's `application.properties`). That produced the 2026-07-10 incident:

- The classifier task was added in M1-597. The operator's config file predated
  it, so it configured the six older tasks (→ DeepSeek) and said nothing about
  the classifier.
- With no per-task line and no shared default to inherit, the classifier fell
  through to its baked `localhost:11434` — a **dead address inside the collector
  container** (the local Ollama is the `ollama:11434` compose sibling; loopback
  points at the collector container itself).
- Every classifier call failed to connect; the worker gracefully stamped each
  post `unknown`. The app booted clean. ~4,700 posts silently rotted before
  anyone noticed.

Earlier drafts of this ticket tried to **detect** that state with a new startup
guard scan. Each detection approach was either misaligned with the guard's
value-based character (config-source ordinal introspection) or heuristic with
false positives (loopback-outlier). The re-scope conclusion: the misconfiguration
class exists only because base-url is per-task-with-per-task-baked-fallbacks. Fix
the config model and the class disappears — no detector needed.

## The fix, in plain English

Give the endpoint the same shared-default-with-optional-override treatment the
**provider** already has (`infochat.llm.default.provider`):

1. **New keys** `infochat.llm.default.base-url` and
   `infochat.llm.default.api-key`. A task with no per-task value inherits them.
   Per-task values still win when present (old wizard-generated operator files
   that fan the endpoint out per-task keep working untouched).
2. **Delete the 7× baked per-task base-url/api-key lines** (both services).
   The bare-metal profiles (`laptop`/`vps`/`pi`) get one profile-scoped
   default (`localhost:11434` — genuinely on-host there). `%remote-llm` gets
   **no default on purpose**: on the profile where a wrong guess silently costs
   money or silently dies, every task must be routed explicitly. The baked
   `%remote-llm` Anthropic route blocks (chat/summarizer/translator, plus the
   `anthropic.languages` line) go too — they carry no api-key (401 out of the
   box), and once the wizard writes only default keys they would resolve as
   per-task values and shadow the operator's real endpoint (budget-breach
   refine, 2026-07-11).
3. **A task with no effective base-url refuses boot**, through the
   *already-existing* required-config startup check, with a message naming both
   keys the operator can set.
4. **The wizard writes one default line instead of seven**, so the operator
   surface finally matches the "one LLM service" reality.

What each deployment shape gets after this:

- **Laptop/vps/pi out of the box** — profile default → works, same as today.
- **Wizard-generated config, any backend** — one `default.base-url` line; a
  **future new task inherits the operator's real endpoint automatically**. The
  incident becomes structurally impossible for wizard configs.
- **Old-format operator file (per-task lines) + a future new task on
  remote-llm** — the new task finds no per-task line and no default → **loud
  boot refusal naming the exact keys**, instead of a dead loopback and silent
  `unknown`s. This is the guard the original M1-603 wanted, obtained for free
  from the required-config machinery.
- **Stale model on an inherited remote endpoint** (baked `llama3.1:8b` against
  DeepSeek) — already caught by the existing M1-577 mismatch scan
  (local-runtime model + remote host).

Every path is either *working* or *loud*. Nothing is silent anymore.

## Guard alignment (why no new scan)

`LlmRouterStartupGuard`'s three scans stay value-based and just consume the
*effective* base-url (per-task, else default) — exactly parallel to how the
local-only scan already treats a cloud-only `default.provider` as an offender
for every task without an override. The `%remote-llm`-boot-refusal behavior is
not a guard feature; it is the existing `assertAllTasksResolve` /
`assertTaskConfigResolvable` required-key check gaining the default-key
fallback.

## Findings surfaced during re-scope (reported, out of scope)

- The checked-in `%remote-llm` profile leaves **all four ingest tasks**
  (security/tagger/entity/classifier) on baked `localhost:11434` — dead in the
  compose deployment. The 2026-07-10 incident was classifier-only only because
  the wizard file happened to override the other three. This ticket's removal
  of the baked per-task lines converts that shape from silent-failure to loud
  boot refusal on `%remote-llm`.
- The baked classifier comment ("ingest stays on the local model even under
  the remote-llm profile") contradicts the D54 wizard behavior (all generative
  tasks → remote endpoint). Left for a spec follow-up; this ticket preserves
  operative behavior.
- Out-of-box `%remote-llm` chat/summarizer/translator were never functional
  anyway (Anthropic base-url with no API key → 401 per call), so the new loud
  boot posture on that profile is strictly an improvement, not a regression of
  a working shape. (Resolved by the budget-breach refine: acceptance 3 now
  removes those baked route blocks outright.)

## Scope

Four production Java files (two providers, router constants, guard), two
`application.properties`, three operator scripts (two wizard + restore.sh's
backend inference), the provider/guard/wiring/restore tests, and three docs
(spec, design, decisions). Embeddings, per-task model keys, the per-task
override axis itself, docker-compose defaults, and the
ingest-local-vs-all-remote design contradiction are all explicitly out of scope
(see `out_of_scope`).
