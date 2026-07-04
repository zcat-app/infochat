---
target: milestone m1
date: 2026-07-04
base: e47964c2e8c9253e7105987be23a36d0c1b927ce
head: main
verdict: CLEAN
findings_count: {critical: 0, high: 0, medium: 0, low: 0}
out_of_model_count: 0
disposition: |
  Milestone-boundary audit of the cumulative m1 diff (385k lines,
  2005 files; base = parent of the M1-001 commit so the whole
  milestone is covered). CLEAN with zero findings and zero
  out-of-model items. Context for weight: this is the boundary
  sweep on top of the per-ticket gates — every security_relevant
  ticket carried its own pre-commit /redteam audit during the
  milestone (records in this directory), and their findings were
  remediated in-branch or ticketed at the time.
---

RED-TEAM VERDICT: CLEAN

Audited the cumulative milestone-m1 diff against docs/spec/security.md
(read in full). Navigated the diff via the sensitive-surface inventory
and targeted reads of the highest-risk code paths rather than linearly.

Surfaces examined and found to deliver their threat-model commitments:

- Authorization evaluation order (InboundRouter.onMessage): transport
  rate cap (step 1.5) fires first with silent over-cap drop; body-size
  cap before normalize; Unicode-normalize (step 1.7) before invite
  consume / group gate / parse; ban check (step 4) after step 3 and
  before step 3.5 with no group-related DB write for banned users;
  probation gate; single users-row snapshot feeds steps 2/3/4/5. Matches
  §Authorization model.
- LLM tool boundary (ChatToolDispatcher): registry allowlist gate,
  per-(user, scope) scope filtering threaded into every tool, length
  caps + limit clamp enforced before any SQL, per-turn call cap and
  cache, typed ValidationError for wrong argument types. Matches
  §Prompt-injection defenses tool table.
- LLM output sanitizer (LlmOutputSanitizer): CLOSED_LIST CI-verified
  against the spec's privileged-tier list, markdown-link flatten runs
  before the closed-list strip, per-occurrence audit rows with
  fail-closed durability (reply not emitted if audit INSERT fails).
  Matches §LLM output sanitizer.
- Invite brute-force + single-use (InviteCodeConsumer): per-(adapter,
  contact_id) counter, non-UUID probes counted (AUDIT-EVASION closed),
  race-safe conditional UPDATE ... RETURNING, audit-before-effect,
  injected Clock for the window. Matches §Invite-code registration.
- SSRF allowlist (infochat-ssrf/IpBlocklist): exhaustive v4/v6 private,
  loopback, link-local (incl. 169.254.169.254), CGNAT, multicast,
  cloud-metadata, plus IPv4-mapped and 6to4/Teredo/NAT64/IPv4-compatible
  transition-form decoding routed through the v4 blocklist, per-call
  host-interface enumeration. Matches §SSRF and outbound connections.
- SimpleX admin claim (SimpleXAdminClaim): constant-time token compare,
  transaction-scoped advisory lock, NOT-EXISTS single-use gate,
  audit-before-effect, no validity oracle (falls through to fixed invite
  reply). Matches §Authorization model / §Per-adapter admin threat profile.
- /audit query (AuditCommandHandler): dynamic WHERE built purely from
  bound placeholders; action uppercased and bound; reads audit_log_view
  (redacted). No injection.
- Chat-intake normalization (InboundRouter.normalize): faithful
  CommonMark fence opener/closer, NFKC + bidi + zero-width strip outside
  fences only. Matches §Ingest pipeline chat-intake parity.
- /unban restored-group-admin disclosure and pre-ban delete path
  present; ExportDataCollector queries are user- or scope-keyed with no
  cross-user leak (group-scope rows are shared group config, not another
  user's private data). Matches §User ban and per-(user, scope) isolation.

No gap found between a threat-model promise and the delivered diff. The
residual weaknesses surfaced during the audit are all explicitly
documented in the threat model itself as accepted risk / operator
mitigations / v2 deferrals, so they are NOT findings:

- SimpleX claim-token re-arm after /revoke-admin — documented in
  §Per-adapter admin threat profile as a residual closed by operator
  hygiene (unset the token); durable token-spent marker is future work.
- Phantom-admin lockout — documented §Authorization model "Blind spot"
  as operator-misconfiguration, not adversary-reachable (seeded contact
  id is trusted config).
- Cross-adapter Sybil (banned on one adapter, fresh identity on another)
  — explicitly deferred to v2 (§What's intentionally NOT in v1).
- Translation-cache cross-scope timing side-channel — explicitly
  accepted as a minor v1 trade-off (§What's intentionally NOT in v1).
- Leaves not freeing the group-admin slot on membership-event-less
  adapters — explicitly a stated NON-COMMITMENT (§Authorization model).

OUT-OF-MODEL: (none)
