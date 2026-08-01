package app.zcat.infochat.provider.summary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import app.zcat.infochat.provider.summary.ClusterProminence.ScoredCluster;
import app.zcat.infochat.provider.summary.ClusterTraversal.Cluster;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;

/**
 * {@link ClusterProminence} unit surface (M1-724): each term in isolation
 * with the others held equal; the §The-design worked example reproducing
 * scores 85/67/60/41 at default weights; raw-magnitude immunity;
 * percentile ties; population separation by source kind; the present-terms
 * denominator; NULL-vs-0 distinctness; the urgent gate; the M1-727 personal
 * bottom gate; determinism and total-order validity over a 200-cluster
 * fixture; the all-NULL-social fixture; and the single-post no-signal
 * cluster.
 */
class ClusterProminenceTest {

    private static final AtomicLong UID_COUNTER = new AtomicLong();

    private final ClusterProminence prominence = new ClusterProminence();

    // ----- the §The-design worked example ----------------------------------

    /**
     * Engineers the 250-cluster population behind the ticket's worked
     * example and asserts the four named clusters score 85 / 67 / 60 / 41
     * at the default weights and order A > D > C > B.
     *
     * <p>Percentiles are population-derived, so the stated percentiles
     * (95/50, 70/60, 50/99/99/40, 20/99/98/30) must FALL OUT of rank
     * arithmetic. Construction (cluster index i runs 1..250):
     *
     * <ul>
     * <li>Corroboration: post i is a singleton tagged {@code T_i..T_250},
     *     so tag {@code T_j} has exactly j active sources and cluster i's
     *     ratio is {@code 1/i} — {@code 250 - i} clusters score strictly
     *     below. A=12 → 95, D=75 → 70, C=125 → 50, B=200 → 20.
     * <li>Scarcity: distinct window volumes 1..250, so volume v has
     *     {@code 250 - v} strictly greater. A=125 → 50, D=100 → 60,
     *     C=150 → 40, B=175 → 30.
     * <li>Social: 200 clusters are Bluesky, the other 50 RSS (NULL
     *     social). Reposts: C tops 199 → 99, B tops 198 → 99. Likes: C
     *     tops 199 → 99, B tops 196 → 98. (A tied-or-distinct top pair
     *     only reaches 99 once the population is ≥ 200:
     *     {@code floor(100·198/200)} = 99.)
     * </ul>
     */
    @Test
    void workedExampleReproducesStatedScores() {
        List<Cluster> clusters = new ArrayList<>();
        Map<Cluster, String> tags = new IdentityHashMap<>();
        // Scarcity volumes: a permutation of 1..250 with the four named
        // clusters pinned.
        int[] volume = new int[251];
        volume[12] = 125;
        volume[75] = 100;
        volume[125] = 150;
        volume[200] = 175;
        int nextVol = 1;
        for (int i = 1; i <= 250; i++) {
            if (volume[i] == 0) {
                while (nextVol == 100 || nextVol == 125 || nextVol == 150 || nextVol == 175) {
                    nextVol++;
                }
                volume[i] = nextVol++;
            }
        }
        // Social values over the 200-cluster Bluesky population: C tops
        // both terms, B is second on reposts (k=198 → 99) and fourth on
        // likes (k=196 → 98).
        Map<Integer, Integer> reposts = new java.util.HashMap<>();
        Map<Integer, Integer> likes = new java.util.HashMap<>();
        int nextSocial = 1;
        for (int i = 1; i <= 250; i++) {
            if (!isBluesky(i)) {
                continue;
            }
            if (i == 125) {
                reposts.put(i, 10_000);
                likes.put(i, 10_000);
            } else if (i == 200) {
                reposts.put(i, 9_999);
                likes.put(i, 197);
            } else {
                reposts.put(i, nextSocial);
                likes.put(i, nextSocial <= 196 ? nextSocial : nextSocial + 2);
                nextSocial++;
            }
        }

        Map<Integer, Cluster> byIndex = new java.util.HashMap<>();
        for (int i = 1; i <= 250; i++) {
            boolean bluesky = isBluesky(i);
            List<String> postTags = new ArrayList<>();
            for (int j = i; j <= 250; j++) {
                postTags.add("tag" + j);
            }
            Post p = post(UUID.randomUUID(),
                    bluesky ? "bluesky" : "rss",
                    bluesky ? reposts.get(i) : null,
                    bluesky ? likes.get(i) : null,
                    volume[i], postTags, List.of("factual"));
            Cluster c = new Cluster("t-w" + i, List.of(p));
            clusters.add(c);
            tags.put(c, "tag" + i);
            byIndex.put(i, c);
        }

        List<ScoredCluster> scored = prominence.score(clusters, tags);
        Map<Cluster, ScoredCluster> byCluster = new IdentityHashMap<>();
        for (ScoredCluster sc : scored) {
            byCluster.put(sc.cluster(), sc);
        }

        ScoredCluster a = byCluster.get(byIndex.get(12));
        ScoredCluster d = byCluster.get(byIndex.get(75));
        ScoredCluster c = byCluster.get(byIndex.get(125));
        ScoredCluster b = byCluster.get(byIndex.get(200));

        // The stated percentiles fall out of rank arithmetic.
        assertEquals(95, a.corroborationPercentile());
        assertEquals(50, a.scarcityPercentile());
        assertNull(a.repostsPercentile(), "RSS: the social term is absent, not zero");
        assertNull(a.likesPercentile());
        assertEquals(70, d.corroborationPercentile());
        assertEquals(60, d.scarcityPercentile());
        assertEquals(50, c.corroborationPercentile());
        assertEquals(99, c.repostsPercentile());
        assertEquals(99, c.likesPercentile());
        assertEquals(40, c.scarcityPercentile());
        assertEquals(20, b.corroborationPercentile());
        assertEquals(99, b.repostsPercentile());
        assertEquals(98, b.likesPercentile());
        assertEquals(30, b.scarcityPercentile());

        // The components reproduce the stated scores by hand-arithmetic:
        // A: (7·95 + 2·50)/9 = 765/9 = 85
        assertEquals(9, a.denominator());
        assertEquals(765, a.numerator());
        assertEquals(85, a.score());
        // D: (7·70 + 2·60)/9 = 610/9 = 67.7 → 67
        assertEquals(610, d.numerator());
        assertEquals(67, d.score());
        // C: (7·50 + 2·99 + 1·99 + 2·40)/12 = 727/12 = 60.5 → 60
        assertEquals(12, c.denominator());
        assertEquals(727, c.numerator());
        assertEquals(60, c.score());
        // B: (7·20 + 2·99 + 1·98 + 2·30)/12 = 496/12 = 41.3 → 41
        assertEquals(496, b.numerator());
        assertEquals(41, b.score());

        // Broad corroboration leads; a viral single-source post places but
        // does not lead; a quiet three-source story beats a viral one.
        List<ScoredCluster> ordered = new ArrayList<>(scored);
        ordered.sort(ClusterProminence.totalOrder());
        assertTrue(ordered.indexOf(a) < ordered.indexOf(d), "A leads D");
        assertTrue(ordered.indexOf(d) < ordered.indexOf(c), "D leads C");
        assertTrue(ordered.indexOf(c) < ordered.indexOf(b), "C leads B");
    }

    @Test
    void comparatorIsATotalOrderOverThe200ClusterFixture() {
        List<Cluster> clusters = new ArrayList<>();
        Map<Cluster, String> tags = new IdentityHashMap<>();
        for (int i = 1; i <= 200; i++) {
            Post p = post(UUID.randomUUID(), i % 2 == 0 ? "bluesky" : "rss",
                    i % 2 == 0 ? i : null, i % 2 == 0 ? 200 - i : null,
                    i, List.of("tag" + (i % 7)), List.of("factual"));
            Cluster c = new Cluster("t-o" + i, List.of(p));
            clusters.add(c);
            tags.put(c, "tag" + (i % 7));
        }
        List<ScoredCluster> scored = prominence.score(clusters, tags);

        // Antisymmetry over every pair.
        for (ScoredCluster x : scored) {
            for (ScoredCluster y : scored) {
                int fwd = ClusterProminence.totalOrder().compare(x, y);
                int bwd = ClusterProminence.totalOrder().compare(y, x);
                assertEquals(Integer.signum(fwd), -Integer.signum(bwd),
                        "antisymmetry: " + x.index() + " vs " + y.index());
            }
        }
        // Transitivity + determinism: sort, shuffle, re-sort to the
        // identical sequence — the comparator is total ON ITS OWN (the
        // captured input index is the explicit final key; sort stability
        // is never load-bearing).
        List<ScoredCluster> sorted = new ArrayList<>(scored);
        sorted.sort(ClusterProminence.totalOrder());
        for (int trial = 0; trial < 5; trial++) {
            List<ScoredCluster> shuffled = new ArrayList<>(sorted);
            Collections.shuffle(shuffled, new Random(42 + trial));
            shuffled.sort(ClusterProminence.totalOrder());
            assertEquals(sorted, shuffled, "shuffle-and-re-sort reproduces the sequence");
        }
    }

    // ----- each term in isolation, the others held equal -------------------

    @Test
    void corroborationRanksShareOfFieldNotRawSourceCount() {
        // The acceptance pin: a 3-source cluster in a tag with 4 active
        // sources outranks a 5-source cluster in a tag with 40 active
        // sources — raw counts rank the tag, the ratio ranks the story.
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID(), s3 = UUID.randomUUID();
        // Tag "narrow": the 3 cluster sources + 1 other = 4 active.
        // Tag "wide": the 5 cluster sources + 35 others = 40 active.
        List<Post> posts = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            posts.add(post(List.of(s1, s2, s3).get(i), "rss", null, null, 50,
                    List.of("narrow"), List.of("factual")));
        }
        posts.add(post(UUID.randomUUID(), "rss", null, null, 50,
                List.of("narrow"), List.of("factual")));
        List<UUID> wideSources = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            wideSources.add(UUID.randomUUID());
            posts.add(post(wideSources.get(i), "rss", null, null, 50,
                    List.of("wide"), List.of("factual")));
        }
        for (int i = 0; i < 35; i++) {
            posts.add(post(UUID.randomUUID(), "rss", null, null, 50,
                    List.of("wide"), List.of("factual")));
        }
        Cluster narrow = new Cluster("t-narrow", posts.subList(0, 3));
        Cluster wide = new Cluster("t-wide", posts.subList(4, 9));
        List<Cluster> others = new ArrayList<>();
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(narrow, "narrow");
        tags.put(wide, "wide");
        // Every remaining post is its own filler cluster in the tag it
        // carries, so the tag-activity counts above hold exactly.
        others.add(new Cluster("t-f1", List.of(posts.get(3))));
        tags.put(others.getLast(), "narrow");
        for (int i = 9; i < posts.size(); i++) {
            others.add(new Cluster("t-fw" + i, List.of(posts.get(i))));
            tags.put(others.getLast(), "wide");
        }
        List<Cluster> all = new ArrayList<>();
        all.add(narrow);
        all.add(wide);
        all.addAll(others);

        List<ScoredCluster> scored = prominence.score(all, tags);
        ScoredCluster sn = scored.get(0);
        ScoredCluster sw = scored.get(1);
        assertTrue(sn.corroborationPercentile() > sw.corroborationPercentile(),
                "3-of-4 beats 5-of-40: " + sn.corroborationPercentile()
                        + " vs " + sw.corroborationPercentile());
    }

    @Test
    void repostsTermRanksWithinTheSameSourceKindOnly() {
        // A Bluesky cluster is NEVER ranked against an RSS cluster on the
        // reposts term: each is the sole member of its kind's population,
        // so both land at percentile 0 — if they shared one population the
        // 1000-repost cluster would sit at 50.
        Post rssPost = post(UUID.randomUUID(), "rss", 5, null, 10,
                List.of("security"), List.of("factual"));
        Post bskyPost = post(UUID.randomUUID(), "bluesky", 1_000, null, 10,
                List.of("security"), List.of("factual"));
        Cluster rss = new Cluster("t-r", List.of(rssPost));
        Cluster bsky = new Cluster("t-b", List.of(bskyPost));
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(rss, "security");
        tags.put(bsky, "security");

        List<ScoredCluster> scored = prominence.score(List.of(rss, bsky), tags);
        assertEquals(0, scored.get(0).repostsPercentile(),
                "RSS cluster ranks against RSS only — population of one");
        assertEquals(0, scored.get(1).repostsPercentile(),
                "1000 raw reposts buys nothing against an empty Bluesky field");
    }

    @Test
    void likesTermInIsolation() {
        Cluster low = singlePostCluster("bluesky", null, 5, 10);
        Cluster high = singlePostCluster("bluesky", null, 500, 10);
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(low, "security");
        tags.put(high, "security");

        List<ScoredCluster> scored = prominence.score(List.of(low, high), tags);
        assertEquals(0, scored.get(0).likesPercentile());
        assertEquals(50, scored.get(1).likesPercentile());
        assertTrue(scored.get(1).score() > scored.get(0).score(),
                "higher likes → higher score, all else equal");
    }

    @Test
    void scarcityRanksTheLeastProlificMemberSource() {
        // The acceptance pin: a single-source cluster from a source with 2
        // posts in the window outranks one from a source with 300.
        Cluster quiet = singlePostCluster("rss", null, null, 2);
        Cluster prolific = singlePostCluster("rss", null, null, 300);
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(quiet, "security");
        tags.put(prolific, "security");

        List<ScoredCluster> scored = prominence.score(List.of(quiet, prolific), tags);
        assertEquals(50, scored.get(0).scarcityPercentile(),
                "one cluster has a strictly busier source below it");
        assertEquals(0, scored.get(1).scarcityPercentile());
        assertTrue(scored.get(0).score() > scored.get(1).score(),
                "quiet-source cluster outranks prolific-source cluster");
    }

    // ----- the structural rules ---------------------------------------------

    @Test
    void rawMagnitudeDoesNotBeatBroadCorroboration() {
        // 50 000 likes must NOT outrank a broadly-corroborated cluster:
        // raw units never share a scale, so the social term only ever sees
        // the percentile — here a population of one, i.e. 0.
        List<Post> rssPosts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            rssPosts.add(post(UUID.randomUUID(), "rss", null, null, 10,
                    List.of("security", "crypto"), List.of("factual")));
        }
        Cluster broad = new Cluster("t-broad", rssPosts);
        Cluster viral = singlePostCluster("bluesky", 50_000, 50_000, 10);
        // "crypto" is carried by every RSS post too (activity 4 → the
        // viral singleton's share is 1/4) while the RSS cluster's own tag
        // is at 4-of-5 — so broad corroboration leads the percentile and
        // the 50k likes only ever see a population of one, i.e. 0.
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(broad, "security");
        tags.put(viral, "crypto");

        List<ScoredCluster> scored = prominence.score(List.of(broad, viral), tags);
        List<ScoredCluster> ordered = new ArrayList<>(scored);
        ordered.sort(ClusterProminence.totalOrder());
        assertEquals(broad, ordered.getFirst().cluster(),
                "50 000 likes does not outrank 4-of-4-source corroboration");
        assertEquals(0, ordered.get(1).likesPercentile(),
                "a lone Bluesky cluster's 50k likes are the bottom of their own population");
    }

    @Test
    void percentileTiesShareAValue() {
        List<Cluster> clusters = new ArrayList<>();
        Map<Cluster, String> tags = new IdentityHashMap<>();
        for (int i = 0; i < 3; i++) {
            Cluster c = singlePostCluster("bluesky", 50, 50, 10);
            clusters.add(c);
            tags.put(c, "security");
        }
        List<ScoredCluster> scored = prominence.score(clusters, tags);
        int first = scored.get(0).repostsPercentile();
        assertEquals(first, scored.get(1).repostsPercentile());
        assertEquals(first, scored.get(2).repostsPercentile());
        assertEquals(scored.get(0).score(), scored.get(1).score());
        assertEquals(scored.get(1).score(), scored.get(2).score(),
                "tied inputs score identically");
    }

    @Test
    void presentTermsDenominatorKeepsEditorialCompetitive() {
        // An RSS cluster and a Bluesky cluster with IDENTICAL corroboration
        // and scarcity percentiles score identically when the Bluesky
        // cluster's social percentiles sit at the population median: the
        // RSS cluster is scored out of 9 (7+2), not zeroed out of 12.
        // Six clusters: three low-ratio/high-volume fillers, then R (RSS),
        // B (Bluesky) and F4 (Bluesky filler) tied on ratio and volume so
        // R and B both land at corroboration 50 and scarcity 50; B's social
        // terms sit at the median of the two-Bluesky population (50).
        List<Cluster> clusters = new ArrayList<>();
        Map<Cluster, String> tags = new IdentityHashMap<>();
        for (int i = 0; i < 3; i++) {
            Cluster f = singlePostCluster("rss", null, null, 300,
                    List.of("low"), List.of("factual"));
            clusters.add(f);
            tags.put(f, "low");
        }
        Cluster f4 = singlePostCluster("bluesky", 0, 0, 2,
                List.of("high", "low"), List.of("factual"));
        Cluster r = singlePostCluster("rss", null, null, 2,
                List.of("high", "low"), List.of("factual"));
        Cluster b = singlePostCluster("bluesky", 10, 10, 2,
                List.of("high", "low"), List.of("factual"));
        clusters.add(f4);
        clusters.add(r);
        clusters.add(b);
        tags.put(f4, "high");
        tags.put(r, "high");
        tags.put(b, "high");
        // "low" is carried by all six posts (activity 6 → ratio 1/6);
        // "high" only by f4/r/b (activity 3 → ratio 1/3): the three fillers
        // sit strictly below, so the tied trio lands at percentile 50.

        List<ScoredCluster> scored = prominence.score(clusters, tags);
        ScoredCluster sr = scored.get(4);
        ScoredCluster sb = scored.get(5);
        assertEquals(50, sr.corroborationPercentile());
        assertEquals(50, sb.corroborationPercentile());
        assertEquals(50, sr.scarcityPercentile());
        assertEquals(50, sb.scarcityPercentile());
        assertEquals(50, sb.repostsPercentile(), "median of a two-cluster population");
        assertEquals(50, sb.likesPercentile());
        assertEquals(9, sr.denominator(), "absent social terms drop OUT of the denominator");
        assertEquals(12, sb.denominator());
        assertEquals(sr.score(), sb.score(),
                "(7·50+2·50)/9 = 50 = (7·50+2·50+2·50+1·50)/12");
        assertEquals(50, sr.score());
    }

    @Test
    void nullAndZeroStayDistinct() {
        // Two fillers sit below the tied pair so absent/zero land at
        // nonzero percentiles: then NULL (term absent, denominator 9) and
        // 0 (term present at percentile 0, denominator 11) produce
        // DIFFERENT scores for otherwise-identical clusters.
        Map<Cluster, String> tags = new IdentityHashMap<>();
        List<Cluster> clusters = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Cluster f = singlePostCluster("rss", null, null, 300,
                    List.of("low"), List.of("factual"));
            clusters.add(f);
            tags.put(f, "low");
        }
        Cluster absent = singlePostCluster("bluesky", null, null, 2,
                List.of("high", "low"), List.of("factual"));
        Cluster zero = singlePostCluster("bluesky", 0, null, 2,
                List.of("high", "low"), List.of("factual"));
        clusters.add(absent);
        clusters.add(zero);
        tags.put(absent, "high");
        tags.put(zero, "high");
        // "low": all four posts (activity 4 → 1/4); "high": the pair
        // (activity 2 → 1/2) — the fillers sit strictly below, the tied
        // pair lands at corroboration 50; scarcity likewise (300,300 < 2,2
        // ranks the pair at 50). The Bluesky reposts population is {zero}
        // alone: absent drops out (NULL), zero ranks at 0.

        List<ScoredCluster> scored = prominence.score(clusters, tags);
        ScoredCluster sa = scored.get(2);
        ScoredCluster sz = scored.get(3);
        assertNull(sa.repostsPercentile(), "NULL reposts: the term is ABSENT");
        assertEquals(0, sz.repostsPercentile(), "0 reposts: present at the bottom percentile");
        assertEquals(50, sa.corroborationPercentile());
        assertEquals(50, sz.corroborationPercentile());
        assertEquals(50, sa.scarcityPercentile());
        assertEquals(50, sz.scarcityPercentile());
        assertEquals(9, sa.denominator());
        assertEquals(11, sz.denominator(), "0 keeps the reposts weight in the denominator");
        assertEquals(50, sa.score(), "(7·50+2·50)/9");
        assertEquals(40, sz.score(), "(7·50+2·50+2·0)/11 = 40.9 → 40");
        assertTrue(sa.score() != sz.score(), "absent and zero score DIFFERENTLY");
    }

    @Test
    void urgentGateOutranksHigherScore() {
        Cluster urgentLow = singlePostCluster("rss", null, null, 300,
                List.of("low"), List.of("urgent"));
        Cluster calmHigh = singlePostCluster("rss", null, null, 2,
                List.of("high", "low"), List.of("factual"));
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(urgentLow, "low");
        tags.put(calmHigh, "high");
        // "low": both posts (activity 2 → 1/2); "high": calmHigh alone
        // (activity 1 → 1/1) — calmHigh leads on corroboration (50 vs 0)
        // and on scarcity (its source's 2 posts vs 300).

        List<ScoredCluster> scored = prominence.score(List.of(urgentLow, calmHigh), tags);
        assertTrue(scored.get(0).urgent());
        assertTrue(scored.get(1).score() > scored.get(0).score(),
                "the non-urgent cluster outscores the urgent one");
        List<ScoredCluster> ordered = new ArrayList<>(scored);
        ordered.sort(ClusterProminence.totalOrder());
        assertEquals(urgentLow, ordered.getFirst().cluster(),
                "the urgent gate ranks ahead of any score");
    }

    // ----- the personal bottom gate (M1-727) --------------------------------

    @Test
    void personalBottomGateSortsLastRegardlessOfUrgencyAndScore() {
        // The gate reads no score component: a personal cluster that is
        // ALSO urgent and would outscore the field still sorts last.
        Cluster personal = singlePostCluster("bluesky", 50_000, 50_000, 2,
                List.of("security"), List.of("personal", "urgent"));
        Cluster quiet = singlePostCluster("rss", null, null, 300,
                List.of("security"), List.of("factual"));
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(personal, "security");
        tags.put(quiet, "security");

        List<ScoredCluster> scored = prominence.score(List.of(personal, quiet), tags);
        assertTrue(scored.get(0).personal(), "every member post carries the label");
        assertTrue(scored.get(0).urgent(), "the urgent top gate still sees the post");
        List<ScoredCluster> ordered = new ArrayList<>(scored);
        ordered.sort(ClusterProminence.totalOrder());
        assertEquals(quiet, ordered.getFirst().cluster(),
                "personal sorts last even when urgent and high-scoring");
        assertEquals(personal, ordered.getLast().cluster());
    }

    @Test
    void bottomGateLeavesNonPersonalRelativeOrderUnchanged() {
        // Urgent-then-score among the non-personal group is the M1-724
        // order unchanged; the personal cluster only ever appends.
        Cluster urgentLow = singlePostCluster("rss", null, null, 300,
                List.of("low"), List.of("urgent"));
        Cluster calmHigh = singlePostCluster("rss", null, null, 2,
                List.of("high", "low"), List.of("factual"));
        Cluster personalHigh = singlePostCluster("bluesky", 50_000, 50_000, 2,
                List.of("high", "low"), List.of("personal"));
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(urgentLow, "low");
        tags.put(calmHigh, "high");
        tags.put(personalHigh, "high");

        List<ScoredCluster> scored = prominence.score(
                List.of(personalHigh, calmHigh, urgentLow), tags);
        List<ScoredCluster> ordered = new ArrayList<>(scored);
        ordered.sort(ClusterProminence.totalOrder());
        assertEquals(List.of(urgentLow, calmHigh, personalHigh),
                ordered.stream().map(ScoredCluster::cluster).toList(),
                "non-personal order is the M1-724 order; personal appends");
    }

    @Test
    void mixedClusterDoesNotTripTheBottomGate() {
        // The all-versus-any choice at the gate, identical to
        // DigestCategorizer.isPersonal: one personal post that clustered
        // with real coverage does not make the cluster personal.
        Post stray = post(UUID.randomUUID(), "rss", null, null, 10,
                List.of("security"), List.of("personal"));
        Post news = post(UUID.randomUUID(), "rss", null, null, 10,
                List.of("security"), List.of("factual"));
        Cluster mixed = new Cluster("t-mixed", List.of(stray, news));
        Cluster personal = singlePostCluster("rss", null, null, 10,
                List.of("security"), List.of("personal"));
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(mixed, "security");
        tags.put(personal, "security");

        List<ScoredCluster> scored = prominence.score(List.of(personal, mixed), tags);
        assertTrue(scored.get(0).personal());
        assertFalse(scored.get(1).personal(),
                "a mixed cluster is NOT personal — one stray member hides no news");
        List<ScoredCluster> ordered = new ArrayList<>(scored);
        ordered.sort(ClusterProminence.totalOrder());
        assertEquals(mixed, ordered.getFirst().cluster());
        assertEquals(personal, ordered.getLast().cluster());
    }

    @Test
    void allNullSocialDigestOrdersOnCorroborationAndScarcity() {
        // The pre-M1-723 state, and the permanent state of an RSS-only
        // deployment: every post carries NULL reposts and likes. No NPE,
        // no division by zero, no silent collapse to a single score.
        // Nested tag lists make the tag activities 3/2/1, so the ratios
        // are 1/3, 1/2, 1/1; volumes 300/50/2 make the third cluster the
        // quietest source too — it leads on both terms.
        List<Cluster> clusters = new ArrayList<>();
        Map<Cluster, String> tags = new IdentityHashMap<>();
        int[] volumes = {300, 50, 2};
        List<List<String>> postTags = List.of(
                List.of("t1"),
                List.of("t1", "t2"),
                List.of("t1", "t2", "t3"));
        String[] assigned = {"t1", "t2", "t3"};
        for (int i = 0; i < 3; i++) {
            Cluster c = singlePostCluster("rss", null, null, volumes[i],
                    postTags.get(i), List.of("factual"));
            clusters.add(c);
            tags.put(c, assigned[i]);
        }
        List<ScoredCluster> scored = prominence.score(clusters, tags);
        for (ScoredCluster sc : scored) {
            assertNull(sc.repostsPercentile());
            assertNull(sc.likesPercentile());
            assertEquals(9, sc.denominator(), "corroboration + scarcity only");
        }
        long distinctScores = scored.stream().map(ScoredCluster::score).distinct().count();
        assertTrue(distinctScores > 1, "no silent collapse to a single score");
        List<ScoredCluster> ordered = new ArrayList<>(scored);
        ordered.sort(ClusterProminence.totalOrder());
        assertEquals(scored.get(2), ordered.getFirst(),
                "broadest-share, quietest-source cluster leads");
        assertEquals(scored.get(0), ordered.getLast(),
                "narrowest-share, busiest-source cluster trails");
    }

    @Test
    void singlePostNoSignalClusterOrdersDeterministically() {
        // No signals at all: social NULL, window count NULL (a hand-built
        // fixture). Scarcity drops out too — the denominator is just the
        // corroboration weight. Two such clusters tie and the input index
        // breaks the tie, deterministically.
        Cluster first = new Cluster("t-x", List.of(post(UUID.randomUUID(), "rss",
                null, null, null, List.of("security"), List.of("factual"))));
        Cluster second = new Cluster("t-y", List.of(post(UUID.randomUUID(), "rss",
                null, null, null, List.of("security"), List.of("factual"))));
        Map<Cluster, String> tags = new IdentityHashMap<>();
        tags.put(first, "security");
        tags.put(second, "security");

        List<ScoredCluster> scored = prominence.score(List.of(first, second), tags);
        assertEquals(7, scored.get(0).denominator(), "scarcity absent: corroboration alone");
        assertNull(scored.get(0).scarcityPercentile());
        List<ScoredCluster> ordered = new ArrayList<>(scored);
        ordered.sort(ClusterProminence.totalOrder());
        assertEquals(List.of(scored.get(0), scored.get(1)), ordered,
                "tied clusters keep input order via the index tiebreak");
    }

    @Test
    void componentsReproduceScoreByHandArithmetic() {
        // For an arbitrary fixture, score == floor(numerator/denominator)
        // and numerator == Σ weight·percentile over present terms.
        List<Cluster> clusters = new ArrayList<>();
        Map<Cluster, String> tags = new IdentityHashMap<>();
        for (int i = 1; i <= 10; i++) {
            Cluster c = singlePostCluster(i % 2 == 0 ? "bluesky" : "rss",
                    i % 2 == 0 ? i * 3 : null, i % 2 == 0 ? i : null,
                    i * 7, List.of("tag" + i), List.of("factual"));
            clusters.add(c);
            tags.put(c, "tag" + i);
        }
        for (ScoredCluster sc : prominence.score(clusters, tags)) {
            long expectedNum = (long) sc.weightCorroboration() * sc.corroborationPercentile();
            int expectedDen = sc.weightCorroboration();
            if (sc.repostsPercentile() != null) {
                expectedNum += (long) sc.weightReposts() * sc.repostsPercentile();
                expectedDen += sc.weightReposts();
            }
            if (sc.likesPercentile() != null) {
                expectedNum += (long) sc.weightLikes() * sc.likesPercentile();
                expectedDen += sc.weightLikes();
            }
            if (sc.scarcityPercentile() != null) {
                expectedNum += (long) sc.weightScarcity() * sc.scarcityPercentile();
                expectedDen += sc.weightScarcity();
            }
            assertEquals(expectedNum, sc.numerator(), "numerator = Σ weight·percentile");
            assertEquals(expectedDen, sc.denominator(), "denominator = Σ present weights");
            assertEquals((int) (expectedNum / expectedDen), sc.score(),
                    "score = floor(numerator/denominator)");
        }
    }

    // ----- helpers ----------------------------------------------------------

    /**
     * The worked example's kind partition: 200 Bluesky clusters and 50 RSS
     * (A=12 and D=75 must be RSS; C=125 and B=200 must be Bluesky).
     */
    private static boolean isBluesky(int i) {
        return i >= 76 || (i <= 26 && i != 12);
    }

    private static Cluster singlePostCluster(String kind, Integer reposts, Integer likes,
                                             Integer windowPosts) {
        return singlePostCluster(kind, reposts, likes, windowPosts,
                List.of("security"), List.of("factual"));
    }

    private static Cluster singlePostCluster(String kind, Integer reposts, Integer likes,
                                             Integer windowPosts, List<String> tags,
                                             List<String> classification) {
        return new Cluster("t-" + UID_COUNTER.incrementAndGet(),
                List.of(post(UUID.randomUUID(), kind, reposts, likes, windowPosts,
                        tags, classification)));
    }

    private static Post post(UUID sourceId, String kind, Integer reposts, Integer likes,
                             Integer windowPosts, List<String> tags,
                             List<String> classification) {
        long n = UID_COUNTER.incrementAndGet();
        return new Post(UUID.randomUUID(), "uid-" + n, sourceId, "Src-" + n,
                "Title " + n, "https://example.com/" + n, "body",
                Instant.parse("2026-07-30T00:00:00Z").plusSeconds(n), tags, classification,
                reposts, likes, kind, windowPosts);
    }
}
