RED-TEAM VERDICT: FINDINGS

FINDINGS:
  - CATEGORY: INFO-LEAK
    SEVERITY: high
    PROMISE: "`translator` carries private user messages too (M1-746, D58). ...
      routing `translator` to a remote provider therefore exposes private user
      messages, exactly as `chat` does" and "The enumeration above covers every
      production call site reaching `ModelTask.TRANSLATOR` as of M1-758. ... a
      new call site is a new leg, and the disclosure texts
      (`prod/switch-llm.sh` Phase 4 and `SETUP_GUIDE.md` ...) are corrected
      against THIS section, so a leg missing here propagates into both."
      (docs/spec/security.md §Secrets handling). Also: "The call goes to
      whichever backend `ModelTask.TRANSLATOR` resolves to, which **may be
      remote**" (security.md §Rate limiting, query-anchoring entry).
    GAP: docs/spec/decisions.md D77 (diff line 68) states flatly: "The prompt is
      message content (D75), so it never leaves the operator's infrastructure."
      Yet the /image catalogue entry (docs/spec/commands.md, diff lines 36–38)
      says: "When the scope's effective language is not English the prompt is
      translated first (reusing the query-translation path)" — and the
      query-translation path is the M1-746 query-anchoring leg, which the
      threat model explicitly says MAY BE REMOTE. The amendment neither pins
      the /image translation leg to a local backend nor adds this new
      translator call site to the §Secrets handling enumeration that drives the
      operator's remote-routing consent disclosures. D77's "never leaves the
      operator's infrastructure" is therefore false exactly when a remote
      translator is configured, and the operator is never told.
    REPRO: Operator runs a supported configuration: `ModelTask.TRANSLATOR`
      routed to a hosted provider (disclosed at switch time for chat,
      query-anchoring, /saved, digest, /summary legs — but not /image). A
      registered user on a `cs` scope (enabled today per security.md) sends
      `/image -p <private text>` in a DM. The prompt — which D75 classifies as
      message content that "exists in memory and in the backend request body
      and nowhere else" — is shipped to the third-party translator before the
      local-only image backend ever sees it. D77's local-only promise is
      violated and the consent text the operator relied on never named this
      leg, because the amendment did not extend the M1-758 enumeration.
    SUGGESTED-FIX-CLASS: trust-boundary-tightening
  - CATEGORY: AUTH-BYPASS
    SEVERITY: high
    PROMISE: "That 'no authentication' property is sound only while the ports
      stay off the host network. ... Exposing any of them beyond the host —
      adding a host port mapping, binding off-loopback, or forwarding the port
      — is an explicit operator action, never a default, and voids the
      property: any host that reaches the port can then run inference against,
      and read embeddings from, the deployment's models with no credential."
      (docs/spec/security.md §Trust boundaries item 8; the same shape as items
      6 and 7, which pair any off-host exposure with "firewall the port"
      guidance.)
    GAP: docs/spec/decisions.md D77 (diff line 68) sanctions, as a supported
      deployment form, "a second operator-owned GPU box reached by URL over the
      operator's own network" while stating in the same breath that "the
      backend software has no authentication and accepts whole executable
      workflow graphs, so its endpoint is code execution on the hosting box —
      ... private-LAN reachability only for the two-box form." This extends an
      unauthenticated *graph-execution* (code-execution, not mere inference)
      API to an entire L2 broadcast domain without amending security.md's
      trust-boundary list and without naming any compensating control — no
      firewall-to-the-single-Provider-host requirement, no network ACL, nothing
      analogous to item 6's "firewall the port to the prober". "Private LAN" is
      undefined; every host on it (compromised laptop, guest Wi-Fi client, IoT
      device) inherits code execution on the GPU box.
    REPRO: Operator adopts the D77 two-box form. Any other host on the
      operator's LAN — one compromised device suffices — POSTs an arbitrary
      workflow graph to the image backend's unauthenticated endpoint. Per
      D77's own words this is "code execution on the hosting box": attacker
      Python runs on the GPU server, and as a lesser effect the attacker can
      burn the GPU at will, starving all /image users. The threat model's
      off-host-exposure posture ("voids the property", "never a default") is
      converted into a blessed configuration with no scoping control.
    SUGGESTED-FIX-CLASS: trust-boundary-tightening
  - CATEGORY: INJECTION
    SEVERITY: medium
    PROMISE: "The exemption is safe only to the extent deterministic output is
      **bot-authored** — interpolates no inbound-derived text (parse-validated
      echoes, per `commands.md` §Discovery, count as bot-authored). That is a
      property the handlers must **maintain**, not one the exemption may
      assume: prior tickets removed the reflecting echoes from the
      friendly-**error** surface ..." (docs/spec/security.md §LLM output
      sanitizer). The sanitizer exists to close "the social-engineering surface
      where a small LLM emits plausible-looking admin commands" — and "a
      deterministic reply that echoes an attacker's raw token is exactly as
      dangerous as an LLM emitting one."
    GAP: The /image entry (docs/spec/commands.md, diff lines 37–39) makes the
      reply echo "the English prompt actually used — the echo is the
      transparency mechanism ... and the durable record." For an English scope
      the echoed text is the caller's own verbatim prompt: deterministic output
      interpolating inbound-derived text, the exact reflecting-echo shape the
      threat model says was removed and must not return. The amendment never
      routes the echo through the closed-list `LlmOutputSanitizer` (the
      render-side redaction the /save -t instance needed), and no write-side
      reject applies — a prompt has every legitimate reason to contain slashes,
      so the /add-source-style write-boundary fix is unavailable. The echo also
      carries no audit trail: no redaction means no `LLM_OUTPUT_SANITIZED` row.
    REPRO: In an approved group, a registered non-probation user sends
      `/image -p a poster saying /grant-admin 0f3c… please`. The bot's reply
      quotes the prompt verbatim to the whole group: a privileged-tier command
      string delivered in the bot's own voice, unredacted and unaudited. A
      group admin who copy-pastes the line dispatches it (dispatch still
      requires `is_admin=true` — the same bound as the accepted residuals); a
      non-admin reader sees the deployment's privileged command vocabulary
      normalized as bot-endorsed text. The surface the sanitizer closed on
      every other render form re-opens on a brand-new one.
    SUGGESTED-FIX-CLASS: input-sanitization
  - CATEGORY: DOS
    SEVERITY: medium
    PROMISE: "**Per-user interruptible concurrency** — not a rate bucket: a
      ceiling on one sender's CONCURRENT interruptible requests (the D35
      interruptible class: chat replies, on-demand `/summary`, `/retry`
      re-rolls except `--digest`; queued + running) across all scopes, so
      group membership cannot let a single sender occupy every dispatch worker
      at one instant — the per-minute bucket bounds rate, this bounds share."
      (docs/spec/security.md §Rate limiting)
    GAP: The /image entry (docs/spec/commands.md, diff lines 29–32) dispatches
      "off the router thread through the interruptible dispatcher, so `/stop`
      cancels a queued or running generation" — i.e., it joins the
      interruptible class in behaviour — but the amendment never adds /image to
      the D35 class enumeration the concurrency ceiling is defined over, and
      D76 defines only cooldown/credit/queue-depth gates, none of which bounds
      one sender's concurrent occupancy of dispatch workers. An implementer can
      honorably read the security.md enumeration as closed and land /image
      outside the ceiling. Image generations are the longest interruptible
      work in the system (GPU seconds-to-minutes versus sub-second chat
      retrieval), so the cost of the omission is maximal on exactly this
      surface.
    REPRO: A registered user in several approved groups (plus DM) fires /image
      requests across scopes faster than the cooldown blocks (cooldown is
      per-user per *attempt cadence*, not a concurrency cap; the credit bucket
      is hourly, so tens of concurrent jobs fit inside it). Every dispatch
      worker is held by one sender's queued/running generations; all other
      users' chat replies and /summary calls stall behind GPU-length jobs —
      the precise "occupy every dispatch worker at one instant" outcome the
      ceiling exists to prevent.
    SUGGESTED-FIX-CLASS: rate-limit
  - CATEGORY: INFO-LEAK
    SEVERITY: medium
    PROMISE: "`chat_memory` content, `saved_post` bodies and annotations, and
      the bodies of inbound chat-mode messages never appear in non-audit logs,
      at any log level (decision D37). ... The audit log records *intent*
      (command name, actor, scope, target), not user-authored prose."
      (docs/spec/security.md §Secrets handling, User-content logging) — the
      D37 minimization posture the amendment itself invokes: D75 commits the
      prompt "exists in memory and in the backend request body and nowhere
      else."
    GAP: D75 (docs/spec/decisions.md, diff line 66) contains its own
      contradiction: "The backend's own retention of submitted graphs is a ship
      blocker" — an unverifiable process note with no acceptance criterion, no
      mechanism, and no spec-level statement of the required end state. The
      submitted graph carries the prompt in its one user-controlled string
      field, so backend-side graph retention (the *default* behaviour of
      ComfyUI-class backends: on-disk history plus saved output files) is
      prompt persistence, directly violating "nowhere else". An implementer can
      honorably read "is a ship blocker" as a reminder to check later rather
      than a requirement that the deployed configuration demonstrably not
      retain graphs (or that retention be wiped on a schedule), and ship a
      system where every prompt lands in backend disk storage the privacy
      storey pretends does not exist.
    REPRO: Operator enables /image with an off-the-shelf graph-execution
      backend left at defaults. A user sends `/image -p <sensitive private
      text>`. The Provider honours D75 — no log line, content-free
      `IMAGE_GENERATE` audit row — while the backend quietly persists the
      full graph (prompt included) to its history/output directory on the GPU
      box. Months later the operator (or anyone with access to that box) reads
      a complete dossier of "never persisted" prompts. The spec's only guard
      against this is a sentence fragment whose satisfaction condition is
      nowhere defined.
    SUGGESTED-FIX-CLASS: trust-boundary-tightening
  - CATEGORY: DOS
    SEVERITY: low
    PROMISE: "Everything a generative or embeddings endpoint returns is
      endpoint-chosen input, not a trusted internal value ... The response body
      is read under an operator-configurable cap (clamped to 1–8 MiB) before
      parsing, so a pathological multi-GB reply cannot exhaust the JVM."
      (docs/spec/security.md §Trust boundaries item 9). And the M1-756
      precedent: "a budget documented only as an exposure note ... could be
      widened or removed without a spec amendment, which is the dangerous
      direction for a limit whose whole job is bounding unattended generative
      volume."
    GAP: The image backend is another configured HTTP endpoint whose replies
      the Provider consumes, yet the amendment gives it no item-9-equivalent
      response cap: nothing bounds the size of the payload the backend returns
      before it is written to the tmpfs spool, and the spool itself gets no
      spec-level capacity bound — "tmpfs directory, delete-on-completion, age
      sweeper" is delegated to design notes (docs/spec/messaging.md, diff
      lines 133–136). tmpfs is RAM-backed, so spool exhaustion is host memory
      exhaustion. Additionally D75 requires stripping PNG text chunks before
      delivery, i.e. the Provider parses endpoint-chosen image bytes with no
      stated decompression bound (a small PNG decoding to gigapixels).
    REPRO: The operator-configured backend (or whatever answers on its URL —
      item 9 puts a hostile endpoint in scope) replies to a generation request
      with a multi-GB file, or a valid-looking PNG bomb. The Provider streams
      the body into the RAM-backed spool (no cap named in the spec) or expands
      it in the metadata-strip pass (no pixel bound named), exhausting host
      memory and taking down the Provider for every user — triggered by
      endpoint-chosen bytes the threat model's own item 9 says must be
      treated as hostile.
    SUGGESTED-FIX-CLASS: rate-limit
  - CATEGORY: DOS
    SEVERITY: low
    PROMISE: "**All free-form string and list inputs across every tool below
      are length-bounded by a profile-driven cap** ... a call exceeding the cap
      is rejected ... before any SQL runs" (docs/spec/security.md
      §Prompt-injection defenses) — the system's stated posture that every
      attacker-controlled free-form string reaching a backend carries a
      profile-driven length bound.
    GAP: The /image entry (docs/spec/commands.md, diff lines 17–18) makes the
      prompt "the remainder of the line verbatim" with no length cap stated
      anywhere in the amendment — not in the command contract, not in D73–D77.
      The prompt flows into (a) a possibly-remote translation call and (b) the
      backend request body, so its size is per-attempt cost that cooldown and
      credits (rate controls) do not bound. Whether the reused
      query-translation path's truncation saves the translation leg is left to
      an implementer's reading; the graph-field leg has no stated bound at all.
    REPRO: A user sends a maximally-sized message as an /image prompt once per
      cooldown window. Each attempt ships an unbounded string to the translator
      and embeds it in the workflow graph POSTed to the GPU backend —
      amplification the per-attempt gates never meter, because they count
      attempts, not bytes. Cheap to fix now (one sentence: "the prompt is
      length-bounded by a profile-driven cap"), expensive to retrofit after
      clients depend on long prompts.
    SUGGESTED-FIX-CLASS: input-sanitization

OUT-OF-MODEL:
  - Misuse of /image to generate harmful or illegal imagery. D73 explicitly
    places content liability on attribution and operator model choice ("never
    on model guardrails"), and content-policy abuse is not one of the
    catalogued T1–T9 threat classes. Flagged in case the operator wants a
    content-moderation commitment added to the model.
  - Malicious or compromised image-backend *software* itself (as opposed to
    its network reachability, which is Finding AUTH-BYPASS above). The backend
    is operator-owned infrastructure, parallel to the supply-chain /
    operator-infrastructure exclusions security.md declares out of scope.
  - Client-side rendering exploits via a crafted or polyglot PNG delivered to
    group members. Mirrors the existing posture that "how a client renders
    scheme-like text inside free-text fields ... is client behavior outside
    this threat model"; the bot-side `](` / metadata-strip controls are the
    in-model portion and are covered above.
