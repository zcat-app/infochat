# v1 verification truth — consolidated, provenance-tagged

> **As of:** 2026-07-13. **Owner:** operator (ubuntu5) + Claude.
> **Why this exists:** the release picture had been reconstructed from stale
> snapshots (6-day-old memories, a 5-day-old `.scratch` handoff) and kept going
> wrong. This doc is the single, dated, source-ranked record. **It replaces
> scattered `.scratch` handoffs as the place to look first.**
>
> **Related:** post-v1 feature backlog → `docs/plan/future-features.md`.

## How to read this (so it can't silently rot)

Every claim carries a **provenance tag**:

- **✅ VERIFIED** — checked against a durable/live source (git, running config,
  live DB, or the committed `docs/plan/live-e2e/` record). Source + date named.
- **🗣 ATTESTED** — stated by the operator, not independently file-verified.
  Authoritative (the operator ran the tests) but recorded as testimony.
- **⇒ DERIVED** — inference from VERIFIED facts (reasoning shown).
- **❓ OPEN** — a genuine gap (none remain as of 2026-07-13; §7 shows resolutions).

**Source hierarchy (highest trust first):** live DB / running config / `git` →
committed docs (`docs/plan/live-e2e/`, `docs/spec`) → `.scratch/*` handoffs →
auto-loaded memories. **Always read the date.** A newer lower-tier source can
still be wrong; an older higher-tier one is not automatically current — check
what changed since its date. (Worked example: `/lang cs` — operator was unsure,
but the 07-10 retest log settles it VERIFIED. File > memory.)

**Maintenance protocol:** update THIS file (bump the as-of date, retag) rather
than writing a new `.scratch` snapshot. When a tag changes, say why.

---

## 1. Code state

- **Board: 642 done, 0 pending / in-progress / in-review / escalated.** Only
  non-terminal: **M1-031** (deferred) and **M1-513** (draft, revisit D49). No
  "v1-done" marker exists. ✅ VERIFIED (git board, 2026-07-13).
- **main = `a19db4c9`** on top of `f9292433` (M1-619 merged). ✅ VERIFIED (git).
- **Pushed to origin @ `a19db4c9`.** ✅ CONFIRMED (operator, 2026-07-13).

## 2. Operator config & LLM

- **`infochat.adapters = simplex,signal`** — both wired. ✅ VERIFIED (config, 07-13).
- **DeepSeek flip DONE:** `provider=deepseek`, `model=deepseek-v4-flash` all
  tasks; reasoning OFF; pre-flip backup preserved; 2026-07-24 sunset risk closed.
  ✅ VERIFIED (config, 07-12).
- **Embeddings:** `nomic-embed-text` on local ollama. ✅ VERIFIED.
- **Bot Signal account = operator's own persistent (+420) number**, not a rental.
  🗣 ATTESTED (07-13).
- **Signal bootstrap-admin ACI: NOT configured.** ✅ CONFIRMED (operator, 07-13) —
  a provider restart will **not** re-create a Signal admin row. (Signal admin WAS
  live-tested 07-04.) → **TODO §9:** operator will add a Signal admin ACI (own
  secondary account) later.

## 3. Live verification ledger — adapters & flows

| Surface | Coverage | Source | Date | Tag |
|---|---|---|---|---|
| **Both adapters — transport/DM round-trip** | admin `/help`, identity, reconnect | live-e2e | 07-04 | ✅ |
| **Signal — admin bootstrap** | D50 pre-seeded ACI, ADMIN-tier list | live-e2e | 07-04 | ✅ |
| **Signal — user leg** | un-invited rejection (S4), `/invite --open` mint+consume (S3), `/vouch` | live-e2e | 07-04 | ✅ |
| **Signal — 3-party group (s07)** | pending→approve, auto-promote, group-admin gate, `/group-timezone`, ACI mention | live-e2e | 07-04 | ✅ |
| **Signal — §6 differences** | edit-fallback (F-live-11), rate 5/s, reconnect, 16KB cap, ordering, typing | live-e2e | 07-04 | ✅ |
| **SimpleX — Phase 4b + groups** | 7 gated scenarios, D51 mentions, GROUP binding; operator confirms SimpleX group flow exercised | live-e2e + operator | 07-03/04 · 07-13 | ✅ / 🗣 |
| **Dual-adapter same deployment** | `/zcash` + LLM chat green on both | live-e2e | 07-04 | ✅ |
| **Migration round-trip** | pack→wipe→restore, RESTORE_EXIT=0 | live-e2e | 07-05 | ✅ |
| **Chat RAG (M1-616/617/618)** | provenance, lexical recall, clarify, "more like this" | operator (ran RAG test today) + m1-619-handoff | 07-13 | 🗣 |
| **Chat RAG (M1-619 calibrated 0.65 cutoff)** | **NOT re-verified live after calibration** | operator + m1-619-handoff | 07-13 | ✅ (not-done) |

**Signal coverage still valid for the current build.** ⇒ DERIVED: `git log`
shows **zero changes to any Signal-adapter source since 2026-07-05**, so the
07-04 live verification covers today's code. ✅ VERIFIED (git, 07-13).

## 3b. Per-command live coverage

**Live-verified (any adapter):**

| Command | Source | Date |
|---|---|---|
| `/help`, `/status`, `/summary`, `/get-tags`, `/list-sources` | live-e2e + retest | 07-04 / 07-10 |
| `/invite` (real mint→consume) | live-e2e S3 + operator ("definitely run") | 07-04 |
| `/vouch` | live-e2e | 07-04 |
| `/approve-group`, auto-promote, `/group-timezone` | live-e2e s07 | 07-04 |
| `/lang cs` (cs output + source titles untranslated) | `.scratch/retest` (`m1594-langcs.log`) | 07-10 |
| `/monero`, `/zcash` | `.scratch/retest` (`m1592-*.log`) + dual-adapter | 07-10 / 07-04 |
| classification/tags render, classifier ingest | `.scratch/retest` | 07-10 |

**Genuinely UNVERIFIED live** (operator not confident; no clear file evidence —
these are the release re-test target, §6):

- `/ban` + intake-block, `/unban`
- `/export`, `/forget`
- `/save`, `/saved`, `/unsave`
- `/add-source` SSRF probe
- prompt-injection defenses
- multi-turn chat memory / recall

🗣 ATTESTED-unverified (operator, 07-13).

## 4. Current runtime / stack state

- **Running:** `postgres` + `ollama` only. **Collector + provider paused.**
  ✅ VERIFIED (07-13).
- **Deployed images: pre-M1-619** (`c731ef63`); the **calibrated 0.65 cutoff was
  NOT re-verified live** after M1-619. DB schema v58. ✅ CONFIRMED (operator +
  handoff, 07-13).
- **Live DB:** users = 4 simplex (1 admin vouched, 3 invited), 0 signal; groups 0;
  post 5,547. ✅ VERIFIED (DB, 07-13).
- **DB was intentionally reset to a prod-state baseline; test users (the rented
  Signal numbers) were removed.** ✅ CONFIRMED (operator, 07-13). So the empty
  Signal/group state is by design, not data loss.

## 5. Signal test-identity resurrection status

- **Bot account:** operator's own number → **intact & controllable.** 🗣 ATTESTED.
- **Rented Norwegian admin/user test-client numbers:** gone (operator no longer
  has them; removed from DB in the reset). 🗣 ATTESTED.
- **signal-cli identity dir** present on host. ✅ VERIFIED.
- **Backup/restore tooling** tars adapter identity dirs; `restore.sh` = "same bot,
  same number, no re-registration." ✅ VERIFIED (script headers).
- ⇒ DERIVED: re-driving Signal live would need a Signal admin ACI configured
  (§2 TODO) + **fresh test-client identities** for the user/group legs. The bot
  side is fine. **Not required for release** (§6.3).

## 6. Remaining verification for release

⇒ DERIVED from §3–§5:

1. **Rebuild images from `a19db4c9`, restart the stack.** (Gate for all live checks.)
2. **Re-verify over the SimpleX test user (contact 7), on the rebuilt image:**
   - **M1-619's calibrated 0.65 cutoff** (never verified live — §4).
   - The **§3b UNVERIFIED command set** (ban/unban, export, forget,
     save/saved/unsave, add-source SSRF, prompt-injection, multi-turn memory).
   - Optional re-confirm of `/lang cs`, `/monero` on the new image.
3. **Signal: not required** — code frozen since the 07-04 verified run.
4. Standard release gates: release `/redteam`, backup/restore + migration on the
   current build, fresh-install smoke — `docs/design/08-verification.md §8.10`.

**Far smaller than "re-test everything on both adapters."**

## 7. Resolved gaps (was OPEN, closed 2026-07-13 by operator)

1. Images — pre-M1-619; calibrated value not re-verified. ✅
2. Latest live test — 07-13 (RAG + M1-619 calibration). ✅
3. Coverage map — folded into §3b. ✅
4. Signal admin ACI — not configured; TODO to add. ✅
5. origin push — confirmed @ `a19db4c9`. ✅
6. Minimal DB — intentional prod-state reset. ✅

## 8. Superseded / demoted sources

- `.scratch/untested-surface-handoff.md` (07-08) — **STALE** (its "Signal
  untested" content is wrong; fixes merged). Do not re-inherit.
- `.scratch/retest/PROGRESS.md` (07-10), `.scratch/m1-619-handoff/` (07-13) —
  folded into §3–§4.
- `docs/plan/v1-completion-plan.md` (2026-05-26) — historical; superseded.

## 9. TODO / owed operational actions

- [ ] **Rebuild collector+provider images from `a19db4c9`, restart** (gates all live checks).
- [ ] **Add a Signal bootstrap-admin ACI** — operator's own secondary account
  (currently none; §2).
- [ ] Re-verify **M1-619 calibrated 0.65 cutoff** live (SimpleX).
- [ ] Live-drive the **§3b UNVERIFIED command set** (SimpleX, rebuilt image).
- [ ] Release gates: `/redteam` at release, backup/restore + migration on current
  build, fresh-install smoke.
