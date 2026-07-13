#!/usr/bin/env python3
"""M1-619 confident-grounding cutoff calibration harness.

Sibling of the M1-616 semantic-threshold harness (M1-616-threshold-eval.py) and
the M1-609 measurement-harness family under this directory (same conventions:
stdlib-only, `docker exec ... psql` for corpus access, a checked-in JSONL fixture
of HUMAN labels, one pretty-JSON result file + a stdout summary). No Python DB
driver and no numpy: pgvector computes the exact cosine distance with the same
`<=>` operator production uses.

WHAT IT CALIBRATES
------------------
`ChatAgent.CONFIDENT_SIMILARITY_CUTOFF` (M1-618): the boundary above which a
grounded chat turn is CONFIDENT (the reply surfaces the "more like this"
getReferences affordance) and below which it is MARGINAL (the reply asks ONE
narrowing question). This gates reply PROSE only — never the retrieved set,
which stays SQL-decided and byte-identical (D19). It sits ABOVE the M1-616
grounding floor: a post must first clear `infochat.chat.semantic-threshold`
(cosine distance < 0.40, i.e. similarity = 1 - distance > 0.60) to be retrieved
at all, so the marginal band is (0.60, cutoff) and this harness sweeps the
cutoff across (0.60, 0.80).

The classification per query MIRRORS `ChatAgent.isMarginalGrounding` exactly:
similarity = 1 - cosine distance; the confidence signal is the MAX numeric
similarity over the retrieved set; a turn grounded only via the LEXICAL arm (no
numeric similarity — a keyword hit with no semantic support) is MARGINAL by
construction; an empty retrieval is neither confident nor marginal (the
general-knowledge path, where no refinement directive fires).

WHY WHOLE-CORPUS, NOT ONE SUBSCRIPTION SCOPE
--------------------------------------------
`CONFIDENT_SIMILARITY_CUTOFF`, like the M1-616 grounding floor it sits above, is
a single scope-INDEPENDENT code constant. Following the M1-616 harness's own
rationale ("a single scope-independent default -> calibrate against the whole
corpus rather than one scope's subscribed subset"), the sweep measures the
best-grounded similarity over the WHOLE READY corpus.

This is also the CONSERVATIVE choice, i.e. the UPPER BOUND on best similarity, in
two independent ways:
  1. SCOPE. Production `SemanticSearchTool` filters to the user's subscribed
     sources; a subset can only REMOVE candidate posts, so the scope-filtered
     best similarity is <= the whole-corpus best similarity (min distance over a
     subset >= min distance over the whole).
  2. INDEX. Production reads the APPROXIMATE HNSW index, which can miss the true
     nearest neighbour and return a further (lower-similarity) post; this harness
     forces an EXACT sequential scan (enable_indexscan=off) for ground-truth
     distances, exactly as M1-616 does.
So the best similarity a real chat turn's `isMarginalGrounding` observes is
<= the best similarity measured here. If the whole-corpus exact best-sim
distribution clusters BELOW a candidate cutoff, the production case that the
constant actually gates clusters at or below that a fortiori. The live
scope-filtered measurement recorded alongside this ticket (test-user
subscription; best-sim ~0.61-0.73) confirms the direction on the real path.

USAGE
-----
  python3 M1-619-confidence-sweep.py \
      --samples docs/plan/m1/spikes/M1-619-query-samples.jsonl \
      --out .scratch/m1-619/sweep.json

  # override the embedding backend / corpus container if they move
  EMBED_BASE_URL=http://localhost:11434/v1 \
  PG_CONTAINER=infochat-postgres-1 PG_USER=infochat PG_DB=infochat \
      python3 M1-619-confidence-sweep.py --out .scratch/m1-619/sweep.json
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

# The M1-616 grounding floor: a post must clear `infochat.chat.semantic-threshold`
# (cosine distance strictly < this) to be retrieved at all. Out of scope for this
# ticket (fixed at the M1-616-calibrated value); it is the floor the cutoff sits
# above, so every candidate cutoff below is > (1 - GROUNDING_GATE) = 0.60.
GROUNDING_GATE = 0.40

# Candidate cutoff sweep. 0.60 .. 0.80 step 0.01 spans the whole marginal band
# (floor 0.60) up past the current 0.75 value, fine enough that the
# confident/marginal split reads as a smooth curve rather than coarse jumps.
CUTOFFS = [round(0.60 + 0.01 * i, 2) for i in range(21)]  # 0.60 .. 0.80
CURRENT_CUTOFF = 0.75  # ChatAgent.CONFIDENT_SIMILARITY_CUTOFF at M1-618

EMBED_BASE_URL = os.environ.get("EMBED_BASE_URL", "http://localhost:11434/v1")
EMBED_MODEL = os.environ.get("EMBED_MODEL", "nomic-embed-text")
PG_CONTAINER = os.environ.get("PG_CONTAINER", "infochat-postgres-1")
PG_USER = os.environ.get("PG_USER", "infochat")
PG_DB = os.environ.get("PG_DB", "infochat")


def pct(n, d):
    return round(100.0 * n / d, 1) if d else None


def embed(query):
    """Embed one query via the provider's OpenAI-compatible /embeddings path —
    body {"model":..,"input":[query]}, returns data[0].embedding. Bit-identical
    to the query vector the running bot produces (same backend, same model). One
    retry on a transient network error, mirroring the family's invoke()."""
    url = EMBED_BASE_URL.rstrip("/") + "/embeddings"
    body = json.dumps({"model": EMBED_MODEL, "input": [query]}).encode()
    last_err = None
    for _ in range(2):
        try:
            req = urllib.request.Request(
                url, data=body, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                return json.load(resp)["data"][0]["embedding"]
        except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
            last_err = exc
    raise RuntimeError(f"embedding failed for {query!r}: {last_err}")


def _psql(sql):
    """Run SQL in the corpus container, tuples-only, tab-separated. Returns the
    list of non-empty output lines. -q suppresses the SET command tags the
    exact-scan pragmas emit so only SELECT rows reach stdout."""
    proc = subprocess.run(
        ["docker", "exec", "-i", PG_CONTAINER, "psql", "-U", PG_USER, "-d", PG_DB,
         "-qtAF", "\t", "-v", "ON_ERROR_STOP=1"],
        input=sql, capture_output=True, text=True)
    if proc.returncode != 0:
        raise RuntimeError(f"psql failed ({proc.returncode}): {proc.stderr.strip()}")
    return [ln for ln in proc.stdout.splitlines() if ln.strip()]


def _vec_literal(vec):
    """pgvector literal `[f1,f2,...]` at full float precision."""
    return "[" + ",".join(repr(float(x)) for x in vec) + "]"


def nearest_distance(vec):
    """Exact MIN cosine distance to any READY post — the strongest possible
    semantic match. enable_indexscan/indexonlyscan=off forces a sequential exact
    scan: `ORDER BY embedding <=> const LIMIT 1` otherwise drives the APPROXIMATE
    HNSW index, which at the default ef_search silently misses true near
    neighbours (M1-616 observed it return 0.549 for a query whose true nearest sat
    at 0.371). Calibrating a similarity boundary demands exact ground truth."""
    lit = _vec_literal(vec)
    sql = (
        "SET enable_indexscan = off; SET enable_indexonlyscan = off; "
        "SELECT min(pe.embedding <=> '" + lit + "'::vector) "
        "FROM post_embedding pe JOIN post p ON p.id = pe.post_id "
        "WHERE p.status = 'READY';")
    return float(_psql(sql)[0])


def lexical_hits(query):
    """Count of READY posts the LEXICAL arm would match, via the real
    `search_tsv` column (V58) and the same `plainto_tsquery('english', ...)`
    production's hybrid retrieval uses. Whole-corpus, matching the scope-
    independent unit of this calibration. Load-bearing ONLY when a query has no
    semantic hit under the gate: it then decides empty (general-knowledge) vs
    lexical-only (marginal by construction). For a query WITH a semantic hit the
    numeric best-sim dominates and this count does not affect the band."""
    lit = query.replace("'", "''")
    sql = ("SELECT count(*) FROM post p WHERE p.status = 'READY' "
           "AND p.search_tsv @@ plainto_tsquery('english', '" + lit + "');")
    return int(_psql(sql)[0])


def classify(best_distance, lex_hits, cutoff):
    """Mirror of ChatAgent.doHandle's refinement-directive decision +
    isMarginalGrounding, for a single candidate cutoff.

      * no semantic hit under the gate AND no lexical hit -> EMPTY
        (general-knowledge path; neither directive fires).
      * no semantic hit under the gate BUT a lexical hit  -> MARGINAL_LEXICAL
        (grounded only via the lexical arm; isMarginalGrounding true by
        construction -> clarify).
      * a semantic hit under the gate, best-sim <  cutoff -> MARGINAL_SEMANTIC
        (weak numeric grounding -> clarify).
      * a semantic hit under the gate, best-sim >= cutoff -> CONFIDENT
        (strong numeric grounding -> more-like-this affordance).
    """
    semantic_grounded = best_distance < GROUNDING_GATE
    if not semantic_grounded:
        return "EMPTY" if lex_hits == 0 else "MARGINAL_LEXICAL"
    best_sim = 1.0 - best_distance
    return "CONFIDENT" if best_sim >= cutoff else "MARGINAL_SEMANTIC"


def ready_count():
    return int(_psql("SELECT count(*) FROM post WHERE status='READY';")[0])


def evaluate(samples):
    queries = []
    for s in samples:
        query = s["query"]
        vec = embed(query)
        best_distance = nearest_distance(vec)
        lex = lexical_hits(query)
        best_sim = round(1.0 - best_distance, 3)
        grounded = best_distance < GROUNDING_GATE
        queries.append({
            "query": query,
            "category": s.get("category", ""),
            "confidence_label": s.get("confidence", ""),
            "num_relevant": s.get("m1616_num_relevant", len(s.get("relevant_uids", []))),
            "note": s.get("note", ""),
            "best_distance": round(best_distance, 3),
            "best_similarity": best_sim,
            "lexical_hits": lex,
            "semantic_grounded": grounded,
            # band at the CURRENT constant, for the "characterise 0.75" section
            "band_at_current": classify(best_distance, lex, CURRENT_CUTOFF),
        })
        print(f"  [{s.get('category',''):<11}] bestSim={best_sim:<6} "
              f"lex={lex:<4} {classify(best_distance, lex, CURRENT_CUTOFF):<17} {query!r}",
              file=sys.stderr)
    return queries


def sweep(queries):
    """Per candidate cutoff, count the bands and — split by confidence label —
    the two calibration rates:
      * affordance_recall = fraction of SHOULD-BE-CONFIDENT queries (label
        'confident': a genuinely-relevant post exists) that land CONFIDENT.
        Higher = fewer good groundings needlessly downgraded to a clarify.
      * spurious_confident = fraction of SHOULD-NOT-GROUND queries (label
        'empty': off-domain, no genuinely-relevant post) that nonetheless land
        CONFIDENT via a spurious near-match under the gate. Lower = fewer
        confident answers built on a coincidental match.
    A well-separated cutoff maximises affordance_recall while holding
    spurious_confident at 0 — 'answer was good, offer more' vs 'too weak, ask
    first'. `separation` is affordance_recall - spurious_confident (a Youden-J
    style single figure of merit)."""
    want_confident = [q for q in queries if q["confidence_label"] == "confident"]
    want_empty = [q for q in queries if q["confidence_label"] == "empty"]
    rows = {}
    for c in CUTOFFS:
        bands = {"CONFIDENT": 0, "MARGINAL_SEMANTIC": 0,
                 "MARGINAL_LEXICAL": 0, "EMPTY": 0}
        for q in queries:
            bands[classify(q["best_distance"], q["lexical_hits"], c)] += 1
        conf_hits = sum(
            1 for q in want_confident
            if classify(q["best_distance"], q["lexical_hits"], c) == "CONFIDENT")
        spur_hits = sum(
            1 for q in want_empty
            if classify(q["best_distance"], q["lexical_hits"], c) == "CONFIDENT")
        aff_recall = pct(conf_hits, len(want_confident))
        spur_conf = pct(spur_hits, len(want_empty))
        rows[str(c)] = {
            "bands": bands,
            "affordance_recall_pct": aff_recall,
            "spurious_confident_pct": spur_conf,
            "separation_pct": (round(aff_recall - spur_conf, 1)
                               if aff_recall is not None and spur_conf is not None
                               else None),
        }
    return {
        "want_confident": len(want_confident),
        "want_empty": len(want_empty),
        "by_cutoff": rows,
    }


def print_summary(result):
    s = result["sweep"]
    print("\n=== M1-619 confident-cutoff sweep summary ===")
    print(f"corpus: {result['corpus']['ready_posts']} READY posts, "
          f"{result['corpus']['embedding_model']} ({result['corpus']['dim']}-d), "
          f"whole-corpus exact scan")
    print(f"grounding gate (M1-616, fixed): distance < {GROUNDING_GATE} "
          f"(similarity > {round(1 - GROUNDING_GATE, 2)})")
    print(f"queries: {s['want_confident']} should-be-confident (on-domain), "
          f"{s['want_empty']} should-not-ground (off-domain)")

    print("\nbest-grounded similarity by query "
          "(band shown at the CURRENT 0.75 cutoff):")
    print(f"  {'bestSim':>7} {'dist':>6} {'lex':>5}  {'label':<9} {'band@0.75':<17} query")
    for q in sorted(result["queries"], key=lambda x: -x["best_similarity"]):
        print(f"  {q['best_similarity']:>7} {q['best_distance']:>6} "
              f"{q['lexical_hits']:>5}  {q['confidence_label']:<9} "
              f"{q['band_at_current']:<17} {q['query'][:46]}")

    print("\nsweep: bands + calibration rates vs cutoff "
          "(C=confident, Ms=marginal-semantic, Ml=marginal-lexical, E=empty):")
    print(f"  {'cutoff':>6} {'C':>3} {'Ms':>3} {'Ml':>3} {'E':>3}  "
          f"{'affRecall%':>10} {'spurConf%':>9} {'separation%':>11}")
    for c in CUTOFFS:
        r = s["by_cutoff"][str(c)]
        b = r["bands"]
        marker = "  <- current" if abs(c - CURRENT_CUTOFF) < 1e-9 else ""
        print(f"  {c:>6} {b['CONFIDENT']:>3} {b['MARGINAL_SEMANTIC']:>3} "
              f"{b['MARGINAL_LEXICAL']:>3} {b['EMPTY']:>3}  "
              f"{str(r['affordance_recall_pct']):>10} "
              f"{str(r['spurious_confident_pct']):>9} "
              f"{str(r['separation_pct']):>11}{marker}")


def load_samples(path):
    return [json.loads(ln) for ln in Path(path).read_text().splitlines() if ln.strip()]


def main():
    default_samples = str(Path(__file__).with_name("M1-619-query-samples.jsonl"))
    ap = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--samples", default=default_samples,
                    help="labelled query JSONL (default: sibling file)")
    ap.add_argument("--out", required=True, help="result JSON path")
    args = ap.parse_args()

    samples = load_samples(args.samples)
    print(f"loaded {len(samples)} labelled queries from {args.samples}", file=sys.stderr)
    corpus = {"ready_posts": ready_count(), "embedding_model": EMBED_MODEL, "dim": 768}
    queries = evaluate(samples)
    result = {
        "harness": "M1-619-confidence-sweep",
        "corpus": corpus,
        "embed_backend": EMBED_BASE_URL.rstrip("/") + "/embeddings",
        "retrieval": "exact pgvector cosine <=> over whole READY corpus (no scope filter)",
        "grounding_gate": GROUNDING_GATE,
        "current_cutoff": CURRENT_CUTOFF,
        "cutoffs": CUTOFFS,
        "queries": queries,
        "sweep": sweep(queries),
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, indent=2))
    print(f"\nwrote {args.out}", file=sys.stderr)
    print_summary(result)


if __name__ == "__main__":
    main()
