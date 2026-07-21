package app.zcat.infochat.provider.help;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provider-side {@code @Startup} observer that builds the conceptual
 * topic corpus (M1-649, decision D68) on every boot. The corpus lives
 * in the {@code doc_embedding} table (V60),
 * {@code doc_kind = "topic"}; the read side
 * {@link CommandIntentIndex#lookupTopic} returns a pointer the M1-666
 * delivery path resolves back to {@link HelpTopicCorpus}.
 *
 * <p><b>Source-of-truth inputs.</b> One intent document per
 * {@link HelpTopicCorpus.Topic}, composed from two in-memory artefacts:
 * <ol>
 *   <li>the topic's title ({@link HelpTopicCorpus.Topic#title()});</li>
 *   <li>the topic's intent words ({@link HelpTopicCorpus.Topic#intentWords()}),
 *       mirroring the role {@code CommandIntentSynonyms} plays for the
 *       command corpus — question/synonym-shaped phrases that lift
 *       recall of phrasings a short user question actually produces.</li>
 * </ol>
 * The intent document is a matching surface only — its text is the
 * probe input to pgvector; the served answer is composed at delivery
 * time (M1-666) from the in-memory {@link HelpTopicCorpus} via the
 * matched topic's {@link HelpTopicCorpus.Topic#answerBundleKey()},
 * never from the indexed text (the match-not-assert invariant D66
 * carries to topics).
 *
 * <p><b>Intent-shaped, not prose.</b> The text embedded here is the
 * title plus intent words — question/synonym-shaped — NOT the answer
 * body. Rationale (ticket M1-649 acceptance item 2): matching a short
 * user question against a long answer embedding is asymmetric and
 * under-recalls the tail phrasings this feature exists to serve;
 * HyDE/query-rewrite are out of scope (D19), so the match surface
 * must itself be question-shaped. Because the match text is now
 * intent-shaped (not prose), the distribution is close to the command
 * corpus; the {@link CommandIntentIndex#TOPIC_SIMILARITY_THRESHOLD}
 * starting value is a named constant with a comment, recalibration a
 * follow-up.
 *
 * <p><b>Content-hash skip.</b> Each row stores a SHA-256 of its
 * source text plus the active {@code infochat.embeddings.model}
 * identifier. The startup preflight diffs the in-memory source set
 * against the stored rows: a row whose hash AND model both match is
 * skipped, so a restart with an unchanged corpus performs zero
 * embedding calls (acceptance: {@code TopicCorpusRetrievalIT.restartWithUnchangedCorpusPerformsNoEmbeddingCall}).
 * A change to the source text OR the model forces a re-embed of the
 * affected rows — a stale vector can never outlive its source text.
 *
 * <p><b>Upsert shape.</b> DELETE-then-INSERT in one transaction,
 * never UPDATE — the {@code doc_embedding} grants withhold UPDATE
 * from the provider role (V60). Pruned rows (topic removed from
 * {@link HelpTopicCorpus#CORPUS}) are DELETEd in the same pass. The
 * DELETE is doc_kind-scoped, so a topic-row prune never touches
 * command_intent rows (acceptance:
 * {@code TopicCorpusRetrievalIT.topicBuilderDeleteNeverTouchesCommandRows}).
 *
 * <p><b>doc_id namespacing.</b> Every topic's {@code doc_id} is
 * {@code "topic:<slug>"} (see {@link HelpTopicCorpus#DOC_ID_PREFIX}).
 * V60's PRIMARY KEY is single-column on {@code doc_id}, so a topic
 * doc_id colliding with a command name would miss on the
 * doc_kind-scoped DELETE and PK-violate on INSERT, rolling back the
 * batch. {@link HelpTopicCorpusTest#topicDocIdsDisjointFromCommandNames}
 * pins the disjointness; the {@code topic:} prefix holds it by
 * construction (no command name contains a colon).
 *
 * <p><b>Failure posture — degrade, don't abort.</b> A failure in
 * {@link EmbeddingProvider#embed} or in the DAO writes is caught by
 * {@link #onStart} and logged at ERROR; the corpus is left empty
 * (cold start) or at its previous-boot state (warm restart). This is
 * the threat-model posture: {@code docs/spec/security.md} §Failure
 * handling binds every LLM-tier failure to a <em>degrade</em> path,
 * never a service-stop. The M1-666 delivery path (the only runtime
 * consumer of topic rows) returns no topic when the corpus is empty,
 * so the operator-visible effect is the chat assistant falling back
 * to its non-topic answer path; helpLookup self-heals on the next
 * restart once the embedding backend is healthy. Mirrors
 * {@code CommandIntentIndexBuilder}'s degrade-don't-abort catch
 * verbatim — same threat-model posture, same recovery shape.
 */
@ApplicationScoped
public class TopicCorpusBuilder {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TopicCorpusBuilder.class);

    private final DocEmbeddingDao dao;
    private final EmbeddingProvider embeddingProvider;
    private final String embeddingModel;

    @Inject
    public TopicCorpusBuilder(DocEmbeddingDao dao,
                              EmbeddingProvider embeddingProvider,
                              @ConfigProperty(name = "infochat.embeddings.model")
                              String embeddingModel) {
        this.dao = dao;
        this.embeddingProvider = embeddingProvider;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Build (or refresh) the topic corpus at Provider startup. Pinned
     * at {@link Priority} {@code 151} so this observer fires AFTER
     * Flyway (priority 100, runs V60) and AFTER
     * {@code CommandIntentIndexBuilder} (priority 150), and BEFORE
     * the default-priority {@code @Observes StartupEvent} observers
     * (priority 1000). The one-priority gap from the command builder
     * is intentional: the two builders write to the same table under
     * different {@code doc_kind}s, so a single bug that double-fires
     * one builder is visible as a duplicate-id log line rather than a
     * silent overwrite; ordering them by priority keeps each
     * independently observable.
     *
     * <p>Failures from {@link #buildCorpus} are caught and logged at
     * ERROR; Provider startup continues with an empty or stale topic
     * corpus. See the class javadoc's failure-posture section for the
     * threat-model rationale.
     */
    void onStart(@Observes @Priority(151) StartupEvent event) {
        log.debug("building {} corpus", HelpTopicCorpus.DOC_KIND);
        long t0 = System.nanoTime();
        try {
            buildCorpus(t0);
        } catch (RuntimeException e) {
            // Degrade, don't abort — same threat-model posture as
            // CommandIntentIndexBuilder.onStart. The M1-666 delivery
            // path returns no topic when the corpus is empty; the
            // operator-visible effect is the chat assistant's
            // non-topic answer path; helpLookup self-heals on next
            // restart once the embedding backend is healthy.
            log.error(
                    "TopicCorpusBuilder: failed building {} corpus — topic delivery will be "
                            + "unavailable (or stale from a prior boot) at chat time. Provider "
                            + "startup continues; the chat path's friendly-degradation posture "
                            + "skips topic answers when no topic is matched. Cause: {}",
                    HelpTopicCorpus.DOC_KIND, e.toString(), e);
        }
    }

    /**
     * Build the corpus. Extracted from {@link #onStart} so the failure
     * posture (catch + log + degrade) wraps the whole body uniformly.
     */
    private void buildCorpus(long t0) {
        // 1. Compose the in-memory source set from the static corpus.
        Map<String, IntentDoc> sourceById = composeSourceSet();

        // 2. Read the stored rows (content-hash + model only — the
        //    embedding vector is never read here; lookupTopic reads
        //    it via pgvector at retrieval time).
        Map<String, DocEmbeddingDao.StoredRow> storedById =
                dao.selectExistingForKind(HelpTopicCorpus.DOC_KIND);

        // 3. Diff into the three buckets.
        List<String> docIdsToEmbed = new ArrayList<>();
        List<String> docIdsToDelete = new ArrayList<>();
        for (Map.Entry<String, IntentDoc> entry : sourceById.entrySet()) {
            String docId = entry.getKey();
            IntentDoc doc = entry.getValue();
            DocEmbeddingDao.StoredRow stored = storedById.get(docId);
            if (stored == null
                    || !doc.contentHash.equals(stored.contentHash())
                    || !embeddingModel.equals(stored.embeddingModel())) {
                docIdsToEmbed.add(docId);
            }
        }
        for (String storedId : storedById.keySet()) {
            if (!sourceById.containsKey(storedId)) {
                docIdsToDelete.add(storedId);
            }
        }

        // 4. Prune disappeared rows (topic removed from CORPUS since
        //    the last boot). The DELETE is doc_kind-scoped inside the
        //    DAO, so command_intent rows are never touched.
        if (!docIdsToDelete.isEmpty()) {
            log.info("pruning {} stale {} rows: {}", docIdsToDelete.size(),
                    HelpTopicCorpus.DOC_KIND, docIdsToDelete);
            dao.delete(HelpTopicCorpus.DOC_KIND, docIdsToDelete);
        }

        // 5. Embed the delta in ONE batch call (the empty batch
        //    short-circuits before the backend is touched — the
        //    content-hash skip).
        if (docIdsToEmbed.isEmpty()) {
            log.debug("{} corpus unchanged — zero embedding calls (content-hash skip)",
                    HelpTopicCorpus.DOC_KIND);
            return;
        }

        List<IntentDoc> docsToEmbed = new ArrayList<>(docIdsToEmbed.size());
        for (String docId : docIdsToEmbed) {
            docsToEmbed.add(sourceById.get(docId));
        }
        List<String> textsToEmbed = new ArrayList<>(docsToEmbed.size());
        for (IntentDoc doc : docsToEmbed) {
            textsToEmbed.add(doc.text);
        }

        List<EmbeddingResult> embeddings;
        try {
            embeddings = embeddingProvider.embed(textsToEmbed);
        } catch (RuntimeException e) {
            throw new IllegalStateException(
                    "TopicCorpusBuilder: embedding backend failed building "
                            + HelpTopicCorpus.DOC_KIND + " corpus (" + textsToEmbed.size()
                            + " rows) — degrading to empty/stale topic corpus", e);
        }
        if (embeddings.size() != textsToEmbed.size()) {
            throw new IllegalStateException(
                    "TopicCorpusBuilder: embedding backend returned "
                            + embeddings.size() + " vectors for " + textsToEmbed.size()
                            + " inputs; the SPI contract is one EmbeddingResult per input");
        }

        // 6. Stage the upsert rows and write in ONE transaction.
        List<DocEmbeddingDao.DocEmbeddingRow> rows = new ArrayList<>(docsToEmbed.size());
        for (int i = 0; i < docsToEmbed.size(); i++) {
            IntentDoc doc = docsToEmbed.get(i);
            rows.add(new DocEmbeddingDao.DocEmbeddingRow(
                    doc.docId, doc.targetRef, doc.contentHash,
                    embeddings.get(i).vector(), embeddingModel));
        }
        dao.upsert(HelpTopicCorpus.DOC_KIND, rows);

        log.info("built {} corpus: {} embedded, {} skipped, {} pruned in {}ms",
                HelpTopicCorpus.DOC_KIND,
                docIdsToEmbed.size(),
                sourceById.size() - docIdsToEmbed.size(),
                docIdsToDelete.size(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Compose the in-memory source set: one {@link IntentDoc} per
     * topic in {@link HelpTopicCorpus#CORPUS}. The document text is
     * the title + intent words — the matching surface, not the
     * answer body (whose bytes never enter {@code doc_embedding}).
     */
    private Map<String, IntentDoc> composeSourceSet() {
        Map<String, IntentDoc> out = new LinkedHashMap<>();
        for (HelpTopicCorpus.Topic topic : HelpTopicCorpus.CORPUS) {
            String text = composeIntentText(topic);
            String hash = sha256(text);
            // doc_id is the namespaced "topic:<slug>"; target_ref is
            // the bare slug the delivery path (M1-666) resolves back
            // to the in-memory Topic via HelpTopicCorpus.byTargetRef.
            out.put(topic.docId(),
                    new IntentDoc(topic.docId(), topic.targetRef(), hash, text));
        }
        return out;
    }

    /**
     * Compose the intent document text for one topic. Mirrors
     * {@code CommandIntentIndexBuilder.composeIntentText}'s shape
     * (header + intent-word list) so the threshold statistics sit
     * close to the command corpus. Stable across restarts
     * (deterministic ordering of intent words; deterministic header),
     * so the content-hash skip is itself deterministic.
     */
    private static String composeIntentText(HelpTopicCorpus.Topic topic) {
        StringBuilder sb = new StringBuilder();
        sb.append(topic.title());
        List<String> intentWords = topic.intentWords();
        if (!intentWords.isEmpty()) {
            sb.append(". Intent words: ");
            for (int i = 0; i < intentWords.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(intentWords.get(i));
            }
            sb.append('.');
        }
        return sb.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JLS spec for every JDK; this
            // is unreachable but rethrown loudly so a future security
            // provider regression fails startup rather than silently
            // producing a degenerate corpus.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** In-memory source document, pre-embedding. */
    private record IntentDoc(String docId, String targetRef,
                             String contentHash, String text) {}
}
