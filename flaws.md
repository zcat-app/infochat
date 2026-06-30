# Setup walkthrough — discovered flaws

Running log of issues found while walking through `prod/setup.sh` and its step
scripts. Pass 1 is a dry simulation (no scripts run); later passes run the script
for real and observe runtime/resource behavior.

Severity legend: **bug** (wrong runtime behavior) · **doc-drift** (stale/incorrect
comment or guide text) · **ux** (confusing but correct) · **question** (needs
confirming).

---

## Falsification pass (2026-06-30) — verification verdicts + ticket map

Every finding was adversarially re-checked (tried to break it, not confirm it).
The pass caught **two bugs in my own fixes** — both now corrected:
- **F17** top-level secrets-guard in `4-llm.sh` BROKE `LlamacppWiringTest` (that
  test seeds no `secrets.env`; the llamacpp branch mints it itself). Moved the
  guard into the **ollama branch** (the only branch that consumes it first). All
  wiring tests now green.
- **F19** handoff told operators to blank `infochat.adapters.simplex.admin-token`
  (a property-key) in `secrets.env`, but `secrets.env` holds the env-var
  `INFOCHAT_SIMPLEX_ADMIN_TOKEN`. Corrected to the env-var name.

**Test verification (definitive):** the 5 wizard wiring tests that drive the real
edited scripts all PASS — `LlamacppWiringTest` 8/8, `SwitchLlmWiringTest` 9/9,
`SimpleXProvisioningWiringTest` 4/4, `AdapterAdminPromptWiringTest` 3/3,
`DoctorWiringTest` 3/3 (BUILD SUCCESS). Confirms F4/F7/F8/F9/F10/F14/F16/F17/F19
edits don't break pinned wizard behavior. (Full `mvn verify` still recommended
before commit.)

| ID | Verdict | Disposition |
|---|---|---|
| F1 | REAL (stale comment) | RESOLVED inline (comment-only; no ticket) |
| F2 | NOT A BUG — intentional global pattern | No ticket (design note) |
| F3 | REAL design tension | Deferred to **v2** spec amendment (out of M1 scope; no M1 ticket) |
| F4 | Fix verified (echoes only) | FIXED |
| F6 | REAL but BY-DESIGN (never-rotate, §7.7.2) | No ticket (documented hand-edit) |
| F7 | Fix verified (fail-fast) | FIXED |
| F8 | Fix verified (2-secrets not test-driven) | FIXED |
| F9 | Fix verified | FIXED |
| F10 | Fix verified (vps→chat_model set→test green) | FIXED |
| F11 | REAL — **HIGH** | → **M1-529** |
| F12 | REAL but defensible | → **M1-532** (may wontfix) |
| F13 | REAL; solution REFINED to `-f && -r` (not `-f` alone) | → **M1-531** |
| F14 | Fix verified (echoes only) | FIXED |
| F15 | REAL; solution REFINED to SELECTIVE delete (not unconditional) | → **M1-530** |
| F16 | Fix verified (test green; substring asserts intact) | FIXED |
| F17 | Fix had a regression — CORRECTED (guard→ollama branch) | FIXED |
| F18 | PLAUSIBLE (trigger unconfirmed) | → **M1-533** (confirm-then-harden) |
| F19 | Fix had a bug — CORRECTED (env-var name) | FIXED |

Falsification refined two solution paths before they became tickets: **F13** must
test `-f && -r` (not `-f` alone — would drop the readability check), and **F15**
must be a SELECTIVE delete of non-chosen adapters (an unconditional data-dir-style
delete would defeat `collect_admin`'s never-rotate idempotency).

---

## F1 — Stale comment: 7-/8- scripts described as not-yet-landed
- **Where:** `prod/setup.sh:19-24` (the `STEPS` registration comment)
- **Severity:** doc-drift (cosmetic, no runtime effect)
- **Detail:** The comment says "The 7-/8- scripts land with M1-385 (blocked on
  this ticket); their entries are wired here so the list is complete when those
  scripts arrive." But `prod/scripts/7-apps.sh` and `prod/scripts/8-verify.sh`
  already exist. The comment predates their landing and should be removed/updated.
- **Status:** open

## F2 — Invalid profile input aborts the whole wizard instead of re-prompting
- **Where:** `prod/scripts/1-profile.sh:37-40`
- **Severity:** ux (low)
- **Detail:** A typo at the profile prompt (e.g. `latop`) prints `FAIL: unknown
  profile` and `exit 1`; under the orchestrator's `set -e` this tears down the
  whole wizard. Resume-state mitigates it (re-run lands back on step 1), but for a
  wizard aimed at non-programmers a `read`-loop that re-asks would be friendlier.
- **Resolution (narrowed):** CONFIRMED as a consistent, intentional global pattern,
  not a per-step bug. Closed-set selections hard-abort on bad input across steps 1
  (profile), 4 (backend), and 6 (adapter list); resume-state is the mitigation. The
  one exception is `6-adapter.sh:collect_admin`, which DOES re-prompt in a loop for
  the required sole-adapter admin. Treat as a single global UX design note: if a
  friendlier wizard is ever wanted, convert closed-set validations to read-loops.
- **Status:** narrowed → global design note (not a bug); no per-step action

## F3 — Hardware profile conflates two axes (sizing vs LLM locality) — v2 redesign
- **Where:** `prod/scripts/1-profile.sh` + D27 (`docs/spec/decisions.md:44`)
- **Severity:** design-note (v2)
- **Detail:** The `laptop|vps|pi|remote-llm` set mixes a hardware-sizing axis
  (laptop/vps/pi) with an LLM-location axis (local vs remote). `remote-llm` is
  essentially "vps-shaped hardware + remote LLM" — most of its tuned values in
  `infochat-collector/.../application.properties` are copied from `%vps`. A cleaner
  model splits into two orthogonal questions: hardware (laptop/vps/pi) × LLM
  location (local/remote). NOT done now because: (a) D27 defines exactly these four
  profiles; (b) `remote-llm` drives ~10 distinct sizing values (poll-interval,
  caps, rate windows, cooldown, concurrency, Stage-1 timeouts, retry-on-outage), so
  it is not redundant with merely picking `remote` in step 4; (c) it anchors the
  step-1→step-4 coupling. Orthogonalizing explodes the profile matrix and
  contradicts D27 → a v2 spec amendment, not a wizard tweak.
- **Status:** deferred to v2 (spec ticket against D27)

## F4 — Step-1 prompt didn't explain the profile or the local-vs-remote split
- **Where:** `prod/scripts/1-profile.sh:31` (the `read -rp` prompt)
- **Severity:** ux (low)
- **Detail:** The bare prompt `Hardware profile (laptop|vps|pi|remote-llm) [laptop]:`
  gave no hint that the profile is a sizing knob, nor that `remote-llm` is the
  odd-one-out (cloud AI) rather than a hardware tier — so an operator could read it
  as hardware-only and later collide with the step-4 backend choice.
- **Fix:** Added a 4-line explanation before the prompt grouping local
  (laptop/vps/pi) vs cloud (remote-llm) and pointing to step 4 for the API key.
- **Status:** FIXED (this session; uncommitted)
- **Note:** This is a code edit to an M1 wizard script — strictly should be a
  tracked M1 ticket commit, not a direct `main` edit. Flagging for when we
  reconcile the session's changes into proper commits.

## F5 — Remote LLM API key collected (step 2) before the backend is chosen (step 4)
- **Where:** `prod/scripts/2-secrets.sh:73-87` vs `prod/scripts/4-llm.sh`
- **Severity:** question → potential ux/bug (verify at step 4)
- **Detail:** The remote API key is prompted in step 2, but whether a remote
  backend is even used is decided in step 4. A `laptop`-profile user planning to
  pick `remote` in step 4 will naturally leave the step-2 key blank ("I'll handle
  the AI later"). Open question: does step 4 re-prompt for / validate the key when
  backend=remote but the key is missing, or does it silently assume step 2 captured
  it? If the latter, the user is stuck with backend=remote and no key.
- **Status:** RESOLVED (not a dead-end). `4-llm.sh:404-417` re-prompts for the key
  when it is missing, so leaving step 2 blank is safe. The remaining issue is
  ergonomic, not functional → reframed as F8 below.

## F8 — Premature remote-key prompt in step 2; step 4 already owns the key
- **Where:** `prod/scripts/2-secrets.sh:73-87` (the optional key prompt)
- **Severity:** ux / simplification
- **Detail:** The remote API key is prompted at step 2, before the operator has
  been asked (step 4) whether they even want a remote backend. Step 4 already
  re-prompts when the key is absent (F5), so the step-2 prompt is redundant and
  asked-too-early. Proposed fix: remove the optional key prompt from step 2 (DB
  password generation stays — step 3 needs it), making the key a single-source
  step-4 concern asked only when backend=remote. Ripple: trim the two step-2 key
  references in `SETUP_GUIDE.md` (the "what the wizard asks" table + the
  complete-example block).
- **Status:** FIXED (this session; uncommitted). Removed the step-2 key prompt +
  the orphaned `dotenv_escape` helper and `defaults` var (now an accepted no-op
  like 0-doctor.sh). Updated `4-llm.sh` comment ("set at step 2" → "already
  recorded"), `SETUP_GUIDE.md` (3 spots), and `prod/config/secrets.env.example`
  (key now appended by step 4). `bash -n` clean on both scripts.

## F7 — Remote backend accepts a BLANK API key silently (asymmetric with base-url)
- **Where:** `prod/scripts/4-llm.sh:407-422`
- **Severity:** bug (low-med) — late failure instead of fail-fast
- **Detail:** A blank base-url fails loudly (`FAIL: a base-url is required`), but a
  blank API key in the re-prompt path is accepted: `if [[ -n "$llm_key" ]]` is
  false, nothing is written, yet the script still sets
  `infochat.llm.*.api-key=${INFOCHAT_LLM_API_KEY}` (expands to empty at boot). The
  remote auth failure then surfaces at runtime, not at the prompt. NOTE: some
  OpenAI-compatible endpoints are keyless, so "require non-empty key" may be too
  strict — consider a confirm ("No key entered — proceed keyless? [y/N]") rather
  than a hard fail. Verify intended behavior before fixing.
- **Status:** FIXED (this session; uncommitted) — per operator decision, a blank
  key for the remote backend is now a hard `FAIL` (fail-fast, mirrors the existing
  base-url check). Known limitation: genuinely keyless OpenAI-compatible endpoints
  are not supported by this path; revisit with a confirm-prompt toggle if needed.

## F6 — Wizard cannot change an already-set secret/API key on re-run
- **Where:** `prod/scripts/2-secrets.sh:57-67, 74-75`
- **Severity:** ux (minor, by-design)
- **Detail:** never-overwrite idempotency means a re-run skips any key already
  present. To rotate a DB password or change the LLM API key you must hand-edit
  `secrets.env`. Documented design intent, but a sharp edge for operators who
  expect re-running the wizard to let them update the key.
- **Status:** open (by-design; doc/UX consideration only)

## F9 — Step 3 has no friendly guard when secrets.env is missing (standalone run)
- **Where:** `prod/scripts/3-postgres.sh:37-38`
- **Severity:** ux / consistency (low)
- **Detail:** Step 3 passes `--env-file "$SECRETS_FILE"` with no existence check.
  In the normal wizard flow step 2 always runs first and creates the file, so this
  cannot happen there. But the guide explicitly supports running a single step
  directly; running `3-postgres.sh` standalone before step 2 yields a raw
  `docker compose` error ("env file not found") instead of a friendly "run step 2
  first" message. `4-llm.sh:189-192` *does* guard its missing-config case, so the
  wizard is inconsistent: step 4 guards the prior step, step 3 trusts it.
- **Fix (proposed):** add a guard at the top of `3-postgres.sh`:
  `[[ -f "$SECRETS_FILE" ]] || { echo "FAIL: $SECRETS_FILE not found; run 2-secrets.sh (wizard step 2) first." >&2; exit 1; }`
- **Status:** FIXED (this session; uncommitted) — added the missing-secrets guard
  at the top of `3-postgres.sh`, mirroring `4-llm.sh`'s pattern. `bash -n` clean.

## F10 — remote-llm profile guard only on the ollama branch, not llamacpp
- **Where:** `prod/scripts/4-llm.sh` — guard at 227-230 (ollama) missing from the
  llamacpp branch (262+)
- **Severity:** bug (med) — documented invariant only half-enforced
- **Detail:** `SETUP_GUIDE.md:240` states "if you picked remote-llm in step 1 you
  must choose remote in step 4." The ollama branch enforces this (empty chat_model
  → FAIL pointing at the remote backend). The llamacpp branch — also a *local*
  backend — has no such guard, so `remote-llm` profile + `llamacpp` proceeds and
  builds a local llama.cpp deployment under remote-llm sizing (tuned for a remote
  LLM: different concurrency/timeouts, retry-on-outage=false). No crash, but a
  contradictory mis-tuned config the wizard's own logic says to reject.
- **Fix (proposed):** add the same `[[ -z "$chat_model" ]]` guard at the top of
  the llamacpp branch (before the --defaults check or right after it).
- **Status:** FIXED (this session; uncommitted) — added the symmetric
  `[[ -z "$chat_model" ]]` guard after the llamacpp branch's --defaults check.
  `bash -n` clean.

## F11 — Remote backend mis-configures embeddings (points at remote, keeps model=nomic-embed-text)
- **Where:** `prod/scripts/4-llm.sh:418-423` (remote branch) + the embeddings
  invariant in `infochat-collector/.../application.properties:508-514`
- **Severity:** bug (HIGH) — remote setups likely cannot vectorize posts
- **Detail:** The remote branch does `set_all_base_urls "$base_url"` (which includes
  `infochat.embeddings.base-url`) and sets `infochat.embeddings.api-key`, but never
  overrides `infochat.embeddings.model` — so it stays the baked default
  `nomic-embed-text` at `dimension=768`. Embeddings are a HARD invariant: model is
  frozen for the deployment's life (`allow-model-change=false`,
  `EmbeddingMetadataStartupGuard` refuses Collector startup on any (model,dimension)
  mismatch; pgvector column is dimension-fixed). Commercial OpenAI-compatible
  providers almost never serve a model named `nomic-embed-text` at 768-dim, so a
  remote setup today either fails the embeddings call (no such model → posts never
  vectorize) or trips the startup guard (wrong dimension → Collector won't boot).
- **Verified facts:** ollama serves chat+embeddings from ONE instance (ollama
  branch: single service, both base-urls → it); llamacpp needs TWO instances
  (gen + `llamacpp-embeddings`) or 1 llamacpp + co-running ollama; the remote
  branch starts NO local embedder.
- **Fix (proposed, design-level):** make embeddings ALWAYS a local nomic-768
  backend; forbid remote embeddings. For `remote` chat, co-run a small Ollama with
  only the nomic embedder (reuse the llamacpp-branch's ollama-embeddings pattern:
  start ollama, `ollama pull nomic-embed-text`, point embeddings.base-url at it).
  Bonus: privacy improvement — embeddings stop leaving the machine, so the remote
  privacy disclosure's embeddings line flips from "sent remote" to "stays local".
- **Related:** F3 (axis conflation), F10 (local-backend guard). Bigger than a
  one-liner — touches the remote branch + the privacy-disclosure text + the guide.
- **Status:** open — awaiting scope decision

## F12 — Custom sources/assets JSON not validated at step 5; a typo fails LATE
- **Where:** `prod/scripts/5-bootstrap.sh:104-118, 165-178`
- **Severity:** ux (low; defensible-by-design)
- **Detail:** Step 5 checks a custom file is *readable* but not that it is valid
  JSON or schema-correct. The guide notes a bad tag makes "the Collector refuse to
  start," so a malformed custom file passes step 5 and only surfaces at step 8
  (health check) — after model downloads and adapter registration. Correct
  validation *boundary* (the Collector owns schema validation; duplicating it in
  bash would violate no-duplicate-validation, and `jq` is not a guaranteed tool),
  but a real UX sharp-edge: a 1-char typo costs the whole setup before it surfaces.
- **Possible mitigation:** a light `jq . "$file" >/dev/null` *syntax* gate when jq
  is present (catches gross malformed JSON early; cannot catch schema/tag errors).
- **Status:** open (logged; defensible as-is)

## F13 — Directory given as a custom path yields a raw `cp` error, not a clear one
- **Where:** `prod/scripts/5-bootstrap.sh:108, 170` (the `[[ -r "$path" ]]` checks)
- **Severity:** ux (low)
- **Detail:** `[[ -r "$path" ]]` is true for a readable *directory*, so `cp` (no
  `-r`) then fails with "omitting directory" under `set -e` — safe (no bad data
  proceeds) but unfriendly. Using `[[ -f "$path" ]]` (regular file) would fail with
  a clear message instead. One-char fix in two places.
- **Status:** open (logged; trivial fix, can fold in later)

## F14 — Custom-path prompts didn't say absolute-vs-relative or relative-from-where
- **Where:** `prod/scripts/5-bootstrap.sh:102, 138`
- **Severity:** ux (low)
- **Detail:** The custom-path prompts gave no hint whether a path is absolute or
  relative, nor — for relative — from where. Verified: this is the ONE wizard input
  resolved against the caller's shell CWD (every other path derives from
  BASH_SOURCE), so relative resolution is genuinely ambiguous.
- **Fix:** Added a 3-line hint before each prompt: absolute, or relative to where
  you ran setup.sh (usually the repo root), with the bundled file named as a
  copy-and-edit example. Resolution behavior unchanged (CWD-relative is the
  standard shell convention; only the wording is clarified).
- **Status:** FIXED (this session; uncommitted)

## F15 — Stale bootstrap-admin credentials not cleaned on adapter de-selection
- **Where:** `prod/scripts/6-adapter.sh` — data-dirs reconciled at line 298, but
  admin creds (`collect_admin`) only ever appended, never deleted
- **Severity:** bug (low/med) — stale-secret hygiene + inconsistency + surprise
- **Detail:** On re-run, the data-dir vars are reconciled (line 298 deletes BOTH
  `INFOCHAT_*_DATA_DIR` then re-adds only chosen). Admin creds are NOT: a run that
  enables `simplex` writes `INFOCHAT_SIMPLEX_ADMIN_TOKEN` (a SECRET); a later run
  with `signal` only drops the simplex config block but leaves that token in
  secrets.env, referenced by nothing. Inert while de-selected, but: (a) inconsistent
  with data-dir handling, (b) a secret lingers at rest, (c) if simplex is re-enabled
  later, `collect_admin`'s "skip if already set" silently REUSES the old token
  instead of prompting fresh.
- **Fix (proposed):** mirror the data-dir pattern — delete the admin var for any
  adapter NOT in `chosen` before the per-adapter loop (keep those that are chosen).
- **Status:** open (logged)

## F16 — Signal's pre-registration requirement is invisible in the wizard prompts
- **Where:** `prod/scripts/6-adapter.sh:169-175` (adapter selection) and the signal
  branch (252-265)
- **Severity:** ux (med) — sets the operator up to hit a wall / late failure
- **Detail:** Verified the wizard does NOT and CANNOT create/verify the Signal
  account (header comment: "Signal phone/captcha enrolment stay manual … does not
  automate the registration"; SignalConfig requires a non-empty registered
  `.account`). Yet the selection prompt never warns that choosing `signal` requires
  a phone number ALREADY registered AND verified out-of-band (captcha + SMS code),
  done UPFRONT — so an operator can pick signal and hit the mandatory account prompt
  (`prompt_required`) with nothing to enter, or pass a not-yet-registered number
  that fails only at step 8. Also missing: guidance on how to add/change Signal
  LATER (re-run `prod/scripts/6-adapter.sh` vs hand-edit — and WHICH file changes:
  `application.properties` for adapter config, `secrets.env` for the admin contact
  id + data-dir — plus a restart to pick it up).
- **Fix:** Add a note at adapter selection: signal needs a pre-registered+verified
  account (wizard can't create it; pointer to the how-to), simplex needs nothing,
  and how to set/change an adapter later + which file + restart.
- **Status:** FIXED (this session; uncommitted) — see edit below.
- **Clarification (two distinct Signal fields — do not conflate):**
  1. Bootstrap admin (`INFOCHAT_SIGNAL_ADMIN_CONTACT_ID`) — OPTIONAL with 2+
     adapters; `collect_admin` accepts blank (only_adapter=0) and the union gate is
     satisfied by another adapter (e.g. the SimpleX token). This is skippable.
  2. Account / phone number (`infochat.adapters.signal.account`) — ALWAYS required
     when Signal is selected (`prompt_required`; SignalConfig needs it at boot).
     This is the field with NO defer path.
- **Open question (for operator):** only the ACCOUNT lacks a defer path. Should
  there be a deferred-account flow (select signal now, register the number later),
  or is the upfront pre-registration messaging (this edit) enough? A defer flow
  needs a SignalConfig change (allow the adapter to start unconfigured/dormant).
- **Extension (this session):** also made the DEDICATED-number requirement explicit
  in both the wizard prompt (6-adapter.sh) and SETUP_GUIDE.md — must be a spare
  SIM/VoIP that can receive a code, NOT a number already on personal Signal (one
  account per number; reusing it would take over the personal account). Deliberately
  documented the off-path "register on a phone, then `signal-cli link` the bot as a
  secondary device" in SETUP_GUIDE.md as a clearly-marked **Advanced alternative
  (possible, NOT recommended/supported)** per operator request — with the
  trade-offs spelled out (phone stays primary; shared account; dedicated number
  only; linked device only gets post-link messages). AdapterAdminPromptWiringTest
  re-run: 3/3 green after the prompt edit; the Option-B addition is guide-only (no
  test surface).

## F17 — Missing secrets.env standalone-run guard across the --env-file steps (F9 family)
- **Where:** `4-llm.sh`, `7-apps.sh`, `8-verify.sh`, `6b-simplex-provision.sh`
- **Severity:** ux / consistency (low)
- **Detail:** Same class as F9 (fixed in step 3): these steps pass
  `--env-file "$SECRETS_FILE"` to compose with no existence check, so a standalone
  run before secrets.env exists (e.g. running `6b` to recover the SimpleX contact
  link — a guide-documented use) yields an opaque compose error instead of a
  pointer to step 2. The guide explicitly supports running single steps directly.
- **Status:** FIXED (this session; uncommitted) — added the F9-style guard to all
  four, uniformly. In `4-llm.sh` it sits after the existing CONFIG_FILE guard; in
  `6b` it sits after the simplex-enabled gate (so it only fires when simplex is
  actually being provisioned). `bash -n` clean on all four.
- **CORRECTION (falsification pass):** the `4-llm.sh` top-level placement was WRONG
  — it broke `LlamacppWiringTest` (that test drives 4-llm.sh with no `secrets.env`;
  the llamacpp/remote branches mint it themselves). Moved the guard INTO the ollama
  branch (the only branch that consumes secrets.env before creating it). 7/8/6b
  guards verified safe (no test, or test seeds an empty secrets.env). All 5 wiring
  tests now green.

## F18 — 6b failure-detector greps operator-influenced output for the word "error"
- **Where:** `prod/scripts/6b-simplex-provision.sh:131` (and the echo at 135)
- **Severity:** bug (low/edge)
- **Detail:** Provisioning success/failure is decided by parsing simplex-chat
  stdout (correct — it exits 0 even on a bad command) with
  `grep -qiE 'bad chat command|(^|[^a-z])error'`. The bot DISPLAY NAME is operator
  input that flows into simplex-chat (`--create-bot-display-name "$display_name"`)
  and can be echoed back, so a name containing the word "error" (e.g. "Error Corp")
  could FALSE-trigger a provisioning failure. Operator input influencing the
  control-flow marker is the smell.
- **Fix (proposed):** tighten the marker to simplex-chat's actual error prefix (the
  spike findings doc `.scratch/simplex-spike-findings.md` likely names one) rather
  than a bare "error" substring; or match only at line start anchored to the
  command-echo format. Verify against the spike before changing.
- **Status:** open (logged)

## F19 — Closing handoff omits the SimpleX claim-token step (default path locks operator out)
- **Where:** `prod/setup.sh` `print_handoff()` — simplex connect block + "First moves"
- **Severity:** bug (med) — final on-screen instruction is wrong for the default
  (SimpleX) path
- **Detail:** Verified `SimpleXAdminClaim.java`: admin is claimed by "the FIRST DM
  whose normalized body equals the token" (gated `WHERE NOT EXISTS … is_admin=TRUE`;
  `InboundRouter` calls `claim()` first on every inbound message). So becoming the
  SimpleX admin REQUIRES DMing the claim-token. But `print_handoff` told every
  operator "You do NOT need an invite code … First moves: 1. Send /help" and never
  mentioned the token. A SimpleX operator following it sends /help first, is
  rejected by the invite gate ("you need an invite"), and is left with no on-screen
  hint that the real next step is the token — contradicting SETUP_GUIDE.md ("your
  first DM must be the exact claim-token"). Correct for Signal (already admin),
  broken for SimpleX (the recommended default).
- **Status:** FIXED (this session; uncommitted) — added the claim-token instruction
  (claim then unset + restart) to the simplex connect block, and a SimpleX caveat to
  "First moves" item 1. `bash -n` clean.
- **CORRECTION (falsification pass):** the first edit told operators to blank
  `infochat.adapters.simplex.admin-token` (the application.properties PROPERTY KEY)
  "in secrets.env" — but secrets.env holds the ENV VAR
  `INFOCHAT_SIMPLEX_ADMIN_TOKEN`. Corrected to the env-var name (matches
  SETUP_GUIDE.md's unset instruction).
