# Anchor-leg characterization — sibling-pair probe + three-arm counterfactual (M1-945)

Committed record of the M1-945 characterization runs (2026-08-27). The
measured contract is cited, never amended: docs/spec/llm.md §Translation
flow + §Determinism boundary, docs/spec/security.md §Prompt-injection
defenses (`semanticSearch` row). **Every table below is SMOKE/descriptive
at n = 12 (rule G1: n < 16) — decision-INFORMING, explicitly NOT
decision-GATING, and never a T1 result.** No production behavior changed
for this record: the diff is test scope + this file only (probe: git diff
names no `src/main` path in any module and no `docs/spec` path).

## What was run

For every active cross-lingual golden row (12 = 3 needs × cs/es/ru/tr)
and its named active English sibling, through the PRODUCTION
`SemanticSearchTool` bean on the frozen stack (M1-929 posture: the
harness injects the bean, constructs nothing):

- **sibling** — the sibling's committed English query on the en eval
  scope (the pair's reference window; en is the strict translator no-op).
- **arm A (shipped anchor path)** — the row's source-language query on
  the row's language scope; the D58 translation leg runs exactly as
  shipped (language-only prompt, greedy, cached).
- **arm B (authored canonical phrasing)** — a committed fixture string,
  one per need, on the en scope. Authored, NEVER translator output
  (D58 (d) forbids an expansion-instructed prompt; the prompt-corpus
  probe asserts every issued prompt is byte-derived from the shipped
  `PROMPT_TEMPLATE`). The fixtures: ai → "latest artificial intelligence
  news", cyber → "latest cybersecurity news", crypto → "latest
  cryptocurrency news". Note: the ai fixture is byte-equal to sibling
  `top-ai-b`'s committed query, so arm B for ai IS the sibling
  measurement by construction (expected, not independent evidence).
- **arm C (raw source query, pre-anchor world)** — the row's
  source-language query on the en eval scope (the strict no-op; zero
  translator calls, asserted by the harness counter).

Scope/cache hygiene: sibling/B/C all dispatch on the en eval scope (the
no-op path touches no `QueryTranslationCache` entry — zero
translator-call delta asserted on every leg); arm A dispatches on its
language's own eval scope. Emission shape asserted per dispatch
(uid/title/url/ready_at/similarity, similarity null on lexical-only
rows). Failure modes exercised: fingerprint-refusal leg (a mismatched
label fingerprint refuses with the named refusal, never a score — green
in every run); no-expansion prompt corpus (CI-runnable unit leg — green).

## Pins

| pin | value |
|---|---|
| DB fingerprint | `ready=5214;max_ready_at=2026-08-24 16:00:57.001472+00;uid_sha256=06ed0de15eefad172062b4b6e3dfb11713e02017b103cc8ab8e064ffbe489727` — label match asserted in EVERY run (unchanged from the corrected baseline) |
| label-set pin | `golden_set_sha256 = 4dfed2d3df02f48b6b0369c8f0323d871d874c25d97769bbc8d445e6ba8e1154` (consumed read-only) |
| embedder | `http://127.0.0.1:18080/v1`, `nomic-embed-text` (llamacpp-embeddings, `nomic-embed-text-v1.5.f16.gguf`) |
| translator | `http://127.0.0.1:18081/v1` (llamacpp, gemma-4-26B GGUF; config model key `llama3.1:8b`) — 0 fallbacks, 0 no-op anchors (12/12 real translations) |
| threshold / limit | 0.40 / 16 |
| harness commit | `16342dab` (ticket branch; test-scope diff only) |
| runs | record run `20260827-205327` (every number below derives from it); determinism pair `20260827-205602`; pre-rebuild run `20260827-203846`. Artifacts (manifest + pairs.jsonl with full per-row emissions) under `.bench/retrieval-eval/characterization/{ts}/` (operator-local) |

## Anchored texts (verified strings — the committed golden-set queries, byte-quoted)

| row | source query (golden set, verbatim) | anchored text (translator output) |
|---|---|---|
| xl-ai-cs-b | `nejnovější zprávy o umělé inteligenci` | `latest news about artificial intelligence` |
| xl-ai-es-b | `últimas noticias sobre inteligencia artificial` | `latest news about artificial intelligence` |
| xl-ai-ru-b | `последние новости об искусственном интеллекте` | `latest news about artificial intelligence` |
| xl-ai-tr-b | `yapay zeka ile ilgili son haberler` | `latest news about artificial intelligence` |
| xl-crypto-cs | `novinky ze světa kryptoměn` | `news from the world of cryptocurrencies` |
| xl-crypto-es | `noticias sobre criptomonedas` | `cryptocurrency news` |
| xl-crypto-ru | `новости о криптовалютах` | `cryptocurrency news` |
| xl-crypto-tr | `kripto para dünyasından haberler` | `news from the crypto world` |
| xl-cyber-cs-b | `kyberbezpečnostní zprávy` | `cybersecurity news` |
| xl-cyber-es-b | `noticias de ciberseguridad` | `cybersecurity news` |
| xl-cyber-ru-b | `новости о кибербезопасности` | `cybersecurity news` |
| xl-cyber-tr-b | `siber güvenlik haberleri` | `cyber security news` |

The English siblings' committed queries (verified strings, correcting
the baseline record's :389-391 misquote per the analysis): `top-ai-b` =
"latest artificial intelligence news"; `top-cyber-b` = "cybersecurity
threats and vulnerabilities"; `top-crypto` = "cryptocurrency and bitcoin
news".

## Per (need × language × arm) raw recall and hit ranks — SMOKE/descriptive, n = 12

Raw recall = expected-uid hits / |E| over the arm's returned window;
hit ranks are 1-based positions of expected uids inside the returned
window; overlap = uids shared between the arm's window and the pair's
sibling window (out of the sibling window's size). Every sibling leg
returns 16 rows except crypto (4).

### need AI (sibling `top-ai-b`, |E| = 16)

| lang | sibling | A (anchor) | B (canonical) | C (raw) |
|---|---|---|---|---|
| cs | 0.750 [1,2,3,4,5,6,8,9,10,14,15,16] | 0.688 [1,2,3,4,5,6,7,8,10,15,16] | 0.750 [1,2,3,4,5,6,8,9,10,14,15,16] | 0.000 [] |
| es | 0.750 (same ranks as cs) | 0.688 (same) | 0.750 (same) | 0.000 [] |
| ru | 0.750 (same) | 0.688 (same) | 0.750 (same) | 0.000 [] |
| tr | 0.750 (same) | 0.688 (same) | 0.750 (same) | 0.000 [] |

Arm A window overlap with sibling: **15/16** every language. B ≡ sibling
by construction (fixture = sibling query). C returns 0–5 rows, none
expected (overlap 0, one 2).

### need CYBER (sibling `top-cyber-b`, |E| = 16)

| lang | sibling | A (anchor) | B (canonical) | C (raw) |
|---|---|---|---|---|
| cs | 0.750 [1,2,3,5,6,7,8,10,12,13,14,15] | 0.250 [4,5,6,12] | 0.375 [2,5,6,8,14,15] | 0.000 [] |
| es | 0.750 (same) | 0.250 (same) | 0.375 (same) | 0.000 [] |
| ru | 0.750 (same) | 0.250 (same) | 0.375 (same) | 0.000 [] |
| tr | 0.750 (same) | 0.250 [2,9,10,11] | 0.375 (same) | 0.000 [] |

Arm A window overlap with sibling: **5, 5, 5, 4** (cs, es, ru, tr).
B's overlap with sibling: 7. The baseline's xl-cyber 0.25 vs sibling
0.75 (record :386-392) is re-derived and restated exactly.

### need CRYPTO (sibling `top-crypto`, |E| = 5)

| lang | sibling | A (anchor) | B (canonical) | C (raw) |
|---|---|---|---|---|
| cs | 0.400 [1,3] | 0.400 [1,3] | 0.800 [1,2,3,5] | 0.000 [] |
| es | 0.400 [1,3] | 0.800 [1,4,5,6] | 0.800 [1,2,3,5] | 0.000 [] |
| ru | 0.400 [1,3] | 0.800 [1,4,5,6] | 0.800 [1,2,3,5] | 0.000 [] |
| tr | 0.400 [1,3] | 0.400 [1,3] | 0.800 [1,2,3,5] | 0.000 [] |

Arm A windows are 7 rows (cs/es/ru) and 5 (tr); overlap with the
sibling's 4-row window: 3 every language. B's overlap: 2. The
baseline's xl-crypto 0.4/0.8/0.8/0.4 vs sibling 0.4 is restated
exactly (byte-identical rows).

## Determinism (D19 legs)

- The IT's double-invocation determinism leg (both internal invocations
  inside one boot: per-arm per-record uid lists and anchored texts
  byte-identical) is green in every run.
- **Record run `20260827-205327` vs `20260827-205602`** (same embedder
  server process, two full JVM invocations): pairs.jsonl **byte-identical**
  — uid lists, orders, anchored texts, and similarity floats.
- **Pre-rebuild run `20260827-203846`** (the embedder server was rebuilt
  between it and the record run — operator stack outage, containers
  recreated): uid SETS identical on all 48 legs, anchored texts
  byte-identical, raw recall identical on all 48 legs; similarity floats
  drift ≤ 0.0023, and ONE leg (the top-cyber-b sibling) reorders 3
  adjacent near-tied rows (same 16-uid set). Attribution: llama.cpp
  embedding numerics are not bit-stable across server restarts; D19's
  same-state determinism holds within a server process (proven by the
  byte-identical pair above). The corrected baseline's cross-run
  byte-identity (M1-944) rode one long-lived server process, consistent
  with this boundary.

## The three decision questions (prose, SMOKE/descriptive at n = 12)

1. **Breadth — how many needs × languages show |Δ| ≥ 0.25 (arm A vs
   sibling)?** 6 of 12 pairs: all four cyber pairs (−0.5 each) and two
   crypto pairs (+0.4 each, es/ru — the anchor BEATS the sibling). AI
   pairs sit at −0.062. The harm is concentrated in cyber; the same
   mechanism helps crypto; ai is nearly unaffected.
2. **Attribution — anchor text vs neighborhood.** For cyber, the
   canonical phrasing (B) recovers only 0.125 of the 0.5 gap (0.25 →
   0.375, sibling 0.75, overlap-with-sibling 5 → 7 of 16): the anchor's
   phrasing is the MINOR share; the dominant share is which window the
   need's phrasing lands in — B itself, a natural English phrasing,
   still misses 0.375 of the sibling's adjudicated set. Corroborating:
   on crypto, two natural English phrasings of the same need (B 0.8 vs
   sibling 0.4) differ by more than the anchor's own effect. The
   embedding space's phrasing sensitivity is of the same magnitude as
   the anchor leg's effect — the anchor is not uniquely broken; the
   space is loose. (This is context for attribution, not a scored
   measurement of the English-side known-defects queue — top-oss
   collision, top-crypto precision noise, duplicate collapsing — which
   stays out of scope here.)
3. **Counterfactual value.** **B:** cyber +0.125 only (does NOT close
   cyber); ai +0.062 to full sibling parity; crypto 0.8 uniformly —
   B does NOT lose crypto's anchor advantage anywhere (it matches arm
   A's best legs and doubles the sibling). **C:** collapse — raw recall
   0.000 on 12 of 12 pairs, windows of 0–5 rows. The anchor leg is
   strictly and decisively better than serving the raw source text on
   the English-centric embedder; removing it is not on the table on
   this evidence.

## Closing note — the eventual fix's queue placement (asked, not answered)

Where does an eventual anchor-leg fix land — before, within, or after
the retrieval campaign — given that M1-937/M1-938 parse the ANCHORED
string (their input distribution changes under any anchor change) and
M1-938/M1-917's lane touches the same fused SQL a fusion-level fix
would touch? And which gate reading applies to an anchor-only delta
(the P15 conservative/liberal fork)? This record does not answer either
question; it is the decision input for the M1-946/947 start gate and
the user's fix-level ruling.
