---
id: M1-705
title: "Rate limiting: build the per-cost-profile bucket partition"
status: pending
created: 2026-07-27
last_updated: 2026-07-27
blocked_by: []
files_budget: 14
files_scope:
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/RateCapBucket.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/messaging/InboundRouter.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/AddSourceCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/QuarantineCommandHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/command/asset/AssetHandler.java
  - infochat-provider/src/main/java/app/zcat/infochat/provider/bundle/BundleKeys.java
  - infochat-provider/src/main/resources/bundles/en.properties
  - infochat-provider/src/main/resources/bundles/cs.properties
  - infochat-provider/src/main/resources/application.properties
  - infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
  - infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerTest.java
  - docs/design/04-security.md
  - docs/design/10-asset-commands.md
complexity: high
risk: high
round_cap: 3
security_relevant: true
migration_touch: false
out_of_scope:
  - >-
    The step-1.5 transport bucket's silent drop. `RateCapBucket.tryAcquire`
    at `InboundRouter:495` and its no-reply overflow are a deliberate
    anti-amplification control (docs/design/04-security.md §4.5 step 1.5:
    "so a hostile flood cannot drive outbound cost via the per-inbound
    fixed-reply paths"). The new buckets sit BEHIND it and never change
    its rate, its key, its stranger-bucket split, or its silence.
  - >-
    `LlmRateCap` and the per-group buckets (`tryAcquireGroupReply`,
    `tryAcquireGroupCommand`, `tryAcquireGroupLlm`). The LLM bucket's
    single-bucket property is load-bearing — chat, `/summary` and
    `/retry` share ONE bucket so a caller cannot bypass the cap by
    switching surfaces (docs/plan/future-features.md §E7). Do not split
    it, re-key it, or route any new bucket's refund through it.
  - >-
    The tool-call-per-turn budget. `ChatAgent.MAX_TOOL_ITERATIONS = 10`
    and `ChatToolDispatcher.TurnContext.DEFAULT_CALL_CAP = 25` differ
    from design §4.9's "5, with a user-facing budget reply", but
    docs/spec/security.md §Rate limiting commits only to "fixed cap" —
    which ships. Which number is right is a decision owed, not this
    ticket's work. Leave both constants and the §4.9 row alone.
  - >-
    The "configurable but clamped to the profile default" clamp for
    `infochat.chat.llm-rate-cap-per-minute`. Design-tier only, no spec
    commitment, and it constrains an existing key rather than building
    the partition. Not implemented here.
  - >-
    The per-source fetch politeness window (design §4.9's 5-minute
    per-source row vs the shipped per-host
    `infochat.fetch.host-min-interval` floor). Collector-side, different
    service, unrelated to the inbound partition.
  - infochat-collector/**
  - any Flyway migration
acceptance:
  - >-
    A per-user cheap-command bucket exists, distinct from the step-1.5
    transport bucket, drawn on by the parser-only / DB-read commands
    enumerated in §Census. Its cap is operator-configurable and
    profile-declared in the Provider's application.properties.
  - >-
    Overflow of the cheap-command bucket sends a friendly reject naming
    the retry delay (docs/design/04-security.md §4.9 "slow down, try
    again in {N}s"), not a silent drop. The reply is a bundle key with
    both an en and a cs value (D43 bilateral keyset).
  - >-
    RateCapBucketTest gains coverage that the cheap-command bucket is
    independent of the transport bucket — draining one does not drain
    the other for the same (adapter, contact) — and that it refills on
    the injected Clock seam like the existing buckets.
  - >-
    AddSourceCommandHandlerTest proves `/add-source` draws on a per-user
    hourly bucket: the 6th `/add-source` within the window is rejected
    with an explanatory reply while a 6th cheap command in the same
    window still succeeds.
  - >-
    AssetHandlerTest proves an asset command draws on the cheap-command
    bucket and never on the LLM bucket, and that over-cap asset traffic
    is rejected rather than silently dropped.
  - >-
    QuarantineCommandHandlerTest proves `/quarantine approve` draws on a
    dedicated per-admin bucket at its own configured cap rather than
    sharing the transport bucket's cap under a namespace — an admin at
    the transport cap's value can still perform quarantine actions up to
    the quarantine cap.
  - >-
    docs/design/04-security.md §4.9's Status column and
    docs/design/10-asset-commands.md §10.10's GAP note are updated to
    describe what now ships; no designed row is deleted to make the
    table match the code.
  - mvn verify from the repo root is green.
test_plan:
  adds:
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/messaging/RateCapBucketTest.java
      — new methods for the cheap-command bucket (independence from the
      transport bucket, refill on the Clock seam, per-(adapter, contact)
      isolation).
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/AddSourceCommandHandlerTest.java
      — hourly `/add-source` bucket: over-cap reject, cheap commands
      unaffected.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/asset/AssetHandlerTest.java
      — asset commands draw the cheap bucket, never the LLM bucket;
      over-cap is a reject.
    - >-
      infochat-provider/src/test/java/app/zcat/infochat/provider/command/QuarantineCommandHandlerTest.java
      — `/quarantine` draws its own per-admin cap.
  preserves:
    - >-
      RateCapBucketTest's existing underCap / overCap / independent /
      refill methods for the TRANSPORT bucket, and every test asserting
      the step-1.5 silent drop. These pin the anti-amplification
      control; tighten around them, never retarget them onto the new
      buckets (engineering-rules §10 — a test that incidentally pins a
      security property is a control).
    - >-
      The per-group cap tests (group reply silent drop, group command
      and group LLM fixed replies) and the LlmRateCap refund path at
      InboundRouter — unchanged behavior, unchanged assertions.
    - all tests currently green on main
spec_refs:
  - docs/spec/security.md §Rate limiting
  - docs/spec/commands.md §Asset commands
decision_refs: []
reviews: {}
overrides: []
aborted_attempts: []
reopens: []
redteam_findings: []
clarity_check: {}
escalation_reason:
---

# M1-705: Rate limiting — build the per-cost-profile bucket partition

## Context

`docs/spec/security.md` §Rate limiting commits to a **partition**:
"Per-user token buckets bound, grouped explicitly so commands that share
a cost profile share a bucket", then names the groups — parser-only /
DB-read commands, asset commands, `/add-source`, chat-mode transport,
LLM-triggering ops, `/quarantine approve`, and the per-group backstops.

What ships is a different shape, not a subset. Verified against the tree:

- `RateCapBucket` (`infochat.rate-cap.inbound-per-minute`, default 60)
  is drawn at `InboundRouter.java:495` for **all** inbound — every
  command and every chat message alike.
- `LlmRateCap` is the one genuinely separate per-user bucket.
- `QuarantineCommandHandler.java:261,303` calls
  `rateCapBucket.tryAcquire("quarantine", actor.id.toString())` — a
  namespace on the same class, so it inherits the same 60/min cap, not
  the designed per-admin 100/min.
- `AddSourceCommandHandler` contains no rate-cap call (grep for
  `RateCap|tryAcquire` in that file returns nothing).
- `AssetHandler` likewise consults no bucket.

So four of the spec's named buckets do not exist as buckets. This is one
structural gap, not four independent features, which is why they are one
ticket: the partition either exists or it does not.

The user-visible half is the overflow behavior. Today every over-cap
command is dropped silently at step 1.5 with no reply; design §4.9
specifies a friendly "slow down, try again in {N}s" for the command
tier. A user who trips the cap currently sees the bot go mute.

Recorded in the doc-drift audit 2026-07-27 (`.scratch/doc-audit.md` §A1);
`docs/design/04-security.md` §4.9 carries the ✗ rows and
`docs/design/10-asset-commands.md` §10.10 the matching GAP note.

## Census

The commands that must draw on the new cheap-command bucket are the ones
`docs/spec/security.md` §Rate limiting names under "Parser-only + DB-read
paginated commands", plus the two commands that get their own bucket.
Enumerate the handler beans and dispose of every one:

    grep -rln "implements CommandHandler\|HelpTier" \
      --include=*.java infochat-provider/src/main/java/app/zcat/infochat/provider/command

| Site | Disposition |
|---|---|
| `/help`, `/status`, `/list-sources`, `/get-sources`, `/get-tags`, `/saved`, `/audit`, `/export`, `/quarantine list` handlers | fix — draw the cheap-command bucket (spec names these explicitly) |
| `AssetHandler` (`/zcash`, `/monero`, …) | fix — draw the cheap-command bucket per design §10.10 ("they share the parser-only command bucket"); see §Notes on the spec/design wording tension |
| `AddSourceCommandHandler` | fix — its own per-user hourly bucket |
| `QuarantineCommandHandler` | fix — dedicated per-admin cap, replacing the namespaced reuse of the transport cap |
| Chat-mode (non-slash) inbound | out-of-scope: transport bucket + `LlmRateCap` already cover it as designed |
| `/summary`, `/retry`, other LLM-triggering commands | out-of-scope: `LlmRateCap` is the designed bucket and it ships |
| Remaining slash handlers not named by the spec (admin lifecycle, group admin, subscription) | disposition is the implementer's call at `start`: either route to the cheap bucket or leave on the transport bucket alone, but state which and why — the spec enumerates a group, not an exhaustive command list |

Re-run the enumeration at `start` and confirm every returned handler has
a disposition before implementing.

## Acceptance

- A per-user **cheap-command** bucket exists as a distinct bucket from
  the step-1.5 transport bucket, configurable and profile-declared.
- Its overflow is a **friendly reject naming the retry delay**, in both
  `en.properties` and `cs.properties`.
- `RateCapBucketTest` proves the cheap bucket and the transport bucket
  are independent for the same `(adapter, contact)` and that the cheap
  bucket refills on the injected `Clock`.
- `AddSourceCommandHandlerTest` proves a per-user hourly `/add-source`
  bucket rejects over-cap while cheap commands in the same window still
  succeed.
- `AssetHandlerTest` proves asset commands draw the cheap bucket, never
  the LLM bucket, and over-cap asset traffic is rejected, not dropped.
- `QuarantineCommandHandlerTest` proves `/quarantine approve` has its
  own per-admin cap independent of the transport cap's value.
- `docs/design/04-security.md` §4.9 and
  `docs/design/10-asset-commands.md` §10.10 are updated to describe what
  ships; no designed row is deleted.
- `mvn verify` from the repo root is green.

## Out-of-scope

The step-1.5 transport bucket keeps its rate, its key, its
stranger-bucket split and — critically — its **silence**: it exists so a
flood cannot drive outbound cost through fixed replies, and the new
buckets sit behind it. `LlmRateCap` is untouched: its single-bucket
property is deliberate (`future-features.md` §E7) and splitting it would
reopen the surface-switching bypass. The tool-call-per-turn budget
(`MAX_TOOL_ITERATIONS = 10`, `DEFAULT_CALL_CAP = 25`) and the
"clamped to the profile default" clamp are both design-tier deviations
with no spec commitment behind them — decisions owed, deliberately not
resolved here. The per-source fetch politeness window is collector-side.
No pre-existing test is retargeted; the transport-bucket and per-group
tests keep their current assertions.

## Notes

- **Why the new buckets must sit behind, not beside, the transport
  bucket.** The friendly reject is outbound cost. It is safe only
  because the transport bucket has already metered the inbound: a
  contact can trigger at most `inbound-per-minute` rejects per minute,
  the same bound that already governs every other fixed reply. Placing a
  chattier bucket in front of step 1.5 would reintroduce exactly the
  amplification the silent drop was built to prevent.

- **A spec/design wording tension the implementer will hit.**
  `docs/spec/security.md` §Rate limiting gives asset commands their own
  bullet ("Share a cache-hit bucket"), while
  `docs/design/10-asset-commands.md` §10.10 says "they share the
  parser-only command bucket". Both are satisfied by routing asset
  commands to the cheap-command bucket, which is what this ticket asks
  for. If review prefers a *distinct* asset bucket, that is a spec
  clarification, not extra implementation — surface it rather than
  guessing.

- **Adjacent pattern to match.** `QuarantineCommandHandler:261` is the
  existing example of a handler-side bucket draw, and
  `InboundRouter:1072` (group command cap → `GROUP_COMMAND_RATE_LIMIT`
  reply) is the existing example of a fixed reject reply at dispatch.
  The group-LLM path at `InboundRouter:1107` also shows the refund
  discipline when a later bucket rejects after an earlier one charged —
  relevant if a command draws two buckets.

- **Bundle keys need both locales.** Adding a key to `en.properties`
  without its `cs.properties` twin fails `BundleLoaderTest` (D43
  bilateral keyset).

- **Numbers.** Design §4.9 records 30/min (cheap commands), 5/hour
  (`/add-source`), 100/min (`/quarantine approve`). Those are the
  designed values and the ticket assumes them; a profile-driven spread
  (as the per-group caps have) is the implementer's call, but the base
  values should not be invented fresh.
