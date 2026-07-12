#!/usr/bin/env python3
"""M1-616 semantic-retrieval threshold calibration harness.

Measures recall@k / precision@k for the chat `semanticSearch` pre-fetch
(SemanticSearchTool, M1-589) across a sweep of the
`infochat.chat.semantic-threshold` cosine-distance gate, against the LIVE READY
corpus. Sibling of the M1-609 measurement-harness family under this directory
(same conventions: stdlib-only, `docker exec ... psql` for corpus access, a
checked-in JSONL fixture of HUMAN labels, one pretty-JSON result file + a stdout
summary). No Python DB driver and no numpy dependency: pgvector computes the
exact cosine distance with the same `<=>` operator production uses.

The read path is reproduced faithfully (verified against SemanticSearchTool.java
and OpenAiCompatibleEmbeddingProvider.java):

  * QUERY EMBEDDING -- POST http://localhost:11434/v1/embeddings with body
    {"model":"nomic-embed-text","input":[query]}, read data[0].embedding (768-d).
    This is the OpenAI-compatible /embeddings shape the provider uses, so the
    query vector is bit-identical to what the running bot would produce.

  * RETRIEVAL -- exact pgvector cosine distance `pe.embedding <=> $q::vector`
    over `post p JOIN post_embedding pe ON p.id = pe.post_id WHERE
    p.status='READY'`, ordered by distance. This is the inner subquery of
    SemanticSearchTool MINUS the `source_subscription` scope filter: the sweep
    is measured over the WHOLE READY corpus deliberately. That is the
    conservative worst case for precision (the largest possible candidate pool =
    the most opportunities for a spurious match) and it matches the query shape
    of the M1-616 provenance probe ("~8% of the corpus, 435/5268, sits under
    0.5"). The threshold is a single scope-independent default, so calibrating
    it against the whole corpus rather than one scope's subscribed subset is the
    right unit of measurement. Production HNSW is approximate, bounded by
    hnsw.max_scan_tuples=20000; for a 5268-post corpus that bound exceeds the
    corpus, so the exact brute-force scan here returns the same rows the
    strict_order iterative HNSW scan would.

  * RETURNED SET at (k, threshold T) -- the posts with distance STRICTLY < T
    (SemanticSearchTool applies `(pe.embedding <=> ?) < ?`, a strict `<`, not
    `<=`), ordered by distance ascending, truncated to k
    (`infochat.chat.semantic-limit`, default 8). The k nearest under T are
    always among the N nearest overall, so a single top-N pull per query
    (N=TOP_N, >> max k) supplies every returned set in the sweep.

METRICS (per query, relevant set R = hand-verified `relevant_uids`):
  recall@k(T)    = |R  ∩ returned(k,T)| / |R|          (skipped when |R| == 0)
  precision@k(T) = |R  ∩ returned(k,T)| / |returned(k,T)|   (skipped when the
                                                             returned set is empty)
OFF-DOMAIN queries carry R = [] on purpose (a security/AI/crypto corpus has no
genuinely-relevant post for "banana bread recipe"). For them precision is
undefined and every returned post is a false positive, so the reported signal is
`fp_count` (size of returned(k,T)) and `nearest_distance` -- i.e. how low T must
go to shut the spurious-grounding door. This is the false-positive band the
ticket calls out.

Labels are HUMAN judgements, never embedding-derived: `relevant_uids` are drawn
by keyword/full-text search on title+body (independent of the vector) and
verified by reading the posts, then finalised by TREC-style pooling of the
retrieved top-N (any retrieved post judged genuinely relevant is folded in; the
rest are confirmed true false positives). See M1-616-query-samples.jsonl `note`
fields and the calibration report for the provenance of each set.

Usage:
  # one-shot: embed every labelled query, sweep, write results + print summary
  python3 M1-616-threshold-eval.py \
      --samples docs/plan/m1/spikes/M1-616-query-samples.jsonl \
      --out .scratch/m1-616/sweep.json

  # override the embedding backend / corpus container if they move
  EMBED_BASE_URL=http://localhost:11434/v1 \
  PG_CONTAINER=infochat-postgres-1 PG_USER=infochat PG_DB=infochat \
      python3 M1-616-threshold-eval.py --out .scratch/m1-616/sweep.json
"""
import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

# Sweep grid. 0.30 .. 0.60 step 0.02 (0.50 -- the current default -- is on the
# grid). Finer than the report needs, so the recall/precision curve reads
# smoothly rather than in coarse jumps.
THRESHOLDS = [round(0.30 + 0.02 * i, 2) for i in range(16)]  # 0.30 .. 0.60
# k values. 8 is the production default (infochat.chat.semantic-limit); the
# others bracket it so the curve's k-sensitivity is visible.
KS = [1, 3, 5, 8, 10, 20]
# Nearest-N pulled per query. Must exceed max(KS) with headroom so every
# returned(k<=20, T) is a prefix of this list, and so the report can pool-judge
# a comfortable neighbourhood around the gate.
TOP_N = 60

EMBED_BASE_URL = os.environ.get("EMBED_BASE_URL", "http://localhost:11434/v1")
EMBED_MODEL = os.environ.get("EMBED_MODEL", "nomic-embed-text")
PG_CONTAINER = os.environ.get("PG_CONTAINER", "infochat-postgres-1")
PG_USER = os.environ.get("PG_USER", "infochat")
PG_DB = os.environ.get("PG_DB", "infochat")


def pct(n, d):
    """Percentage helper shared with the M1-609 harness family."""
    return round(100.0 * n / d, 1) if d else None


def embed(query):
    """Embed one query via the provider's OpenAI-compatible /embeddings path.

    Body {"model":..,"input":[query]}; returns data[0].embedding as a list of
    float. One retry on a transient network error (mirrors the family's
    invoke()).
    """
    url = EMBED_BASE_URL.rstrip("/") + "/embeddings"
    body = json.dumps({"model": EMBED_MODEL, "input": [query]}).encode()
    last_err = None
    for attempt in range(2):
        try:
            req = urllib.request.Request(
                url, data=body, headers={"Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=60) as resp:
                payload = json.load(resp)
            return payload["data"][0]["embedding"]
        except (urllib.error.URLError, TimeoutError, ConnectionError) as exc:
            last_err = exc
    raise RuntimeError(f"embedding failed for {query!r}: {last_err}")


def _psql(sql):
    """Run SQL in the corpus container, tuples-only, tab-separated. Returns the
    list of non-empty output lines."""
    # -q suppresses command tags (e.g. the "SET" line the exact-scan pragmas in
    # rank_top_n emit) so only SELECT rows reach stdout; SELECT output is unaffected.
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


def rank_top_n(vec):
    """The TOP_N nearest READY posts by EXACT cosine distance, ascending. Returns
    [{"uid":str,"d":float,"title":str}]. Titles are newline/tab-stripped so the
    tab-separated output stays one row per post.

    `enable_indexscan/indexonlyscan = off` forces a sequential exact scan: an
    `ORDER BY embedding <=> const LIMIT n` otherwise drives the HNSW index, whose
    APPROXIMATE search at the default ef_search silently misses true near
    neighbours (observed: it returned 0.549 as the nearest post for a query whose
    true nearest sits at 0.371). Calibrating a distance THRESHOLD demands exact
    ground-truth distances, not the approximate set production's index returns;
    the HNSW-recall gap is a separate concern (noted in the report, relevant to
    M1-617), not something this measurement should inherit."""
    lit = _vec_literal(vec)
    sql = (
        "SET enable_indexscan = off; SET enable_indexonlyscan = off; "
        "SELECT p.uid, (pe.embedding <=> '" + lit + "'::vector) AS d, "
        "regexp_replace(coalesce(p.title,''), E'[\\t\\n\\r]+', ' ', 'g') "
        "FROM post_embedding pe JOIN post p ON p.id = pe.post_id "
        "WHERE p.status = 'READY' "
        "ORDER BY d ASC LIMIT " + str(TOP_N) + ";")
    out = []
    for line in _psql(sql):
        parts = line.split("\t")
        uid, d = parts[0], float(parts[1])
        title = parts[2] if len(parts) > 2 else ""
        out.append({"uid": uid, "d": d, "title": title})
    return out


def band_counts(vec, thresholds):
    """Whole-corpus count of READY posts under each threshold -- the candidate
    pool size the gate would admit (before the top-k cap). Characterises how
    loose the gate is per query."""
    lit = _vec_literal(vec)
    filters = ", ".join(
        f"count(*) FILTER (WHERE d < {t})" for t in thresholds)
    sql = (
        "SELECT " + filters + " FROM (SELECT (pe.embedding <=> '" + lit +
        "'::vector) AS d FROM post_embedding pe JOIN post p ON p.id = pe.post_id "
        "WHERE p.status = 'READY') t;")
    row = _psql(sql)[0].split("\t")
    return {str(t): int(c) for t, c in zip(thresholds, row)}


def relevant_distances(vec, uids):
    """Exact distance to each labelled relevant uid (they may rank beyond TOP_N
    for a hard paraphrase, so fetch explicitly)."""
    if not uids:
        return {}
    lit = _vec_literal(vec)
    in_list = ", ".join("'" + u.replace("'", "''") + "'" for u in uids)
    sql = (
        "SELECT p.uid, (pe.embedding <=> '" + lit + "'::vector) AS d "
        "FROM post_embedding pe JOIN post p ON p.id = pe.post_id "
        "WHERE p.status = 'READY' AND p.uid IN (" + in_list + ");")
    return {ln.split("\t")[0]: float(ln.split("\t")[1]) for ln in _psql(sql)}


def returned_set(top, threshold, k):
    """The uids SemanticSearchTool would fold in at (threshold, k): posts with
    distance strictly < threshold, nearest first, capped at k."""
    under = [row["uid"] for row in top if row["d"] < threshold]
    return under[:k]


def ready_count():
    return int(_psql("SELECT count(*) FROM post WHERE status='READY';")[0])


def evaluate(samples):
    queries = []
    for s in samples:
        query = s["query"]
        relevant = list(s.get("relevant_uids", []))
        vec = embed(query)
        top = rank_top_n(vec)
        queries.append({
            "query": query,
            "category": s.get("category", ""),
            "relevant_uids": relevant,
            "num_relevant": len(relevant),
            "note": s.get("note", ""),
            "top": top,
            "relevant_distances": relevant_distances(vec, relevant),
            "band_counts": band_counts(vec, THRESHOLDS),
        })
        print(f"  embedded+ranked: [{s.get('category','')}] {query!r} "
              f"(|R|={len(relevant)}, nearest d={top[0]['d']:.3f})",
              file=sys.stderr)
    return queries


def summarise(queries):
    """Macro-average recall@k / precision@k over the on-corpus queries per
    (threshold, k), plus the off-domain false-positive band."""
    on_corpus = [q for q in queries if q["num_relevant"] > 0]
    off_domain = [q for q in queries if q["num_relevant"] == 0]

    by_threshold = {}
    for t in THRESHOLDS:
        per_k = {}
        for k in KS:
            recalls, precisions = [], []
            for q in on_corpus:
                ret = returned_set(q["top"], t, k)
                relevant = set(q["relevant_uids"])
                hits = len(relevant & set(ret))
                recalls.append(hits / len(relevant))
                if ret:
                    precisions.append(hits / len(ret))
            # off-domain: every returned post is a false positive
            fp_counts = [len(returned_set(q["top"], t, k)) for q in off_domain]
            per_k[str(k)] = {
                "macro_recall_pct": pct(sum(recalls), len(recalls)) if recalls else None,
                "macro_precision_pct": (
                    pct(sum(precisions), len(precisions)) if precisions else None),
                "offdomain_queries_with_fp": sum(1 for c in fp_counts if c > 0),
                "offdomain_total": len(off_domain),
                "offdomain_mean_fp": (
                    round(sum(fp_counts) / len(fp_counts), 2) if fp_counts else None),
            }
        mean_candidates = (
            round(sum(q["band_counts"][str(t)] for q in queries) / len(queries), 1)
            if queries else None)
        by_threshold[str(t)] = {
            "mean_candidate_pool": mean_candidates,
            "by_k": per_k,
        }

    # nearest-distance-to-any-post for each off-domain query: the single number
    # that says "a gate at or below this shuts this spurious query out entirely".
    offdomain_nearest = {
        q["query"]: round(q["top"][0]["d"], 3) for q in off_domain if q["top"]}
    return {
        "on_corpus_queries": len(on_corpus),
        "offdomain_queries": len(off_domain),
        "offdomain_nearest_distance": offdomain_nearest,
        "by_threshold": by_threshold,
    }


def print_summary(result):
    s = result["summary"]
    print("\n=== M1-616 threshold sweep summary ===")
    print(f"corpus: {result['corpus']['ready_posts']} READY posts, "
          f"{result['corpus']['embedding_model']} ({result['corpus']['dim']}-d)")
    print(f"queries: {s['on_corpus_queries']} on-corpus, "
          f"{s['offdomain_queries']} off-domain")
    print(f"\nrecall@8 / precision@8 / off-domain-FP vs threshold "
          f"(k=8, the production default):")
    print(f"  {'T':>5} {'recall@8':>9} {'prec@8':>8} {'poolAvg':>8} "
          f"{'offFP?':>7} {'offMeanFP':>10}")
    for t in THRESHOLDS:
        bt = s["by_threshold"][str(t)]
        k8 = bt["by_k"]["8"]
        print(f"  {t:>5} {str(k8['macro_recall_pct']):>9} "
              f"{str(k8['macro_precision_pct']):>8} "
              f"{str(bt['mean_candidate_pool']):>8} "
              f"{str(k8['offdomain_queries_with_fp'])+'/'+str(k8['offdomain_total']):>7} "
              f"{str(k8['offdomain_mean_fp']):>10}")
    print("\noff-domain nearest-post distance (gate at/below this excludes it):")
    for q, d in sorted(s["offdomain_nearest_distance"].items(), key=lambda kv: kv[1]):
        print(f"  {d:>6}  {q}")


def load_samples(path):
    return [json.loads(ln) for ln in Path(path).read_text().splitlines() if ln.strip()]


def main():
    default_samples = str(Path(__file__).with_name("M1-616-query-samples.jsonl"))
    ap = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--samples", default=default_samples,
                    help="labelled query JSONL (default: sibling file)")
    ap.add_argument("--out", required=True, help="result JSON path")
    args = ap.parse_args()

    samples = load_samples(args.samples)
    print(f"loaded {len(samples)} labelled queries from {args.samples}",
          file=sys.stderr)
    corpus = {
        "ready_posts": ready_count(),
        "embedding_model": EMBED_MODEL,
        "dim": 768,
    }
    queries = evaluate(samples)
    result = {
        "harness": "M1-616-threshold-eval",
        "corpus": corpus,
        "embed_backend": EMBED_BASE_URL.rstrip("/") + "/embeddings",
        "retrieval": "exact pgvector cosine <=> over whole READY corpus (no scope filter)",
        "thresholds": THRESHOLDS,
        "ks": KS,
        "top_n": TOP_N,
        "queries": queries,
        "summary": summarise(queries),
    }
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    Path(args.out).write_text(json.dumps(result, indent=2))
    print(f"\nwrote {args.out}", file=sys.stderr)
    print_summary(result)


if __name__ == "__main__":
    main()
