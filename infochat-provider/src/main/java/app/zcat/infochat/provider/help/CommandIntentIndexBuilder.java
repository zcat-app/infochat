package app.zcat.infochat.provider.help;

import app.zcat.infochat.llm.EmbeddingProvider;
import app.zcat.infochat.llm.EmbeddingResult;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.messaging.CommandIntentSynonyms;
import app.zcat.infochat.provider.messaging.HelpCommandHandler;
import app.zcat.infochat.provider.messaging.HelpCommandHandler.CommandHelp;
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
 * Provider-side {@code @Startup} observer that builds the command-intent
 * embedding corpus (M1-664) on every boot. The corpus lives in the
 * {@code doc_embedding} table (V60), {@code doc_kind = "command_intent"};
 * the chat-side {@code HelpLookupTool} reads it back at chat-turn time.
 *
 * <p><b>Source-of-truth inputs.</b> One intent document per catalogue
 * command, composed from three runtime artefacts:
 * <ol>
 *   <li>the command name ({@link CommandHelp#command()});</li>
 *   <li>the catalogue's one-line short-help line, resolved through
 *       {@link BundleLoader#get(String)} (English — the bundle's stable
 *       fallback; {@link CommandIntentSynonyms}'s English-only seed note
 *       makes this a deliberate single-language corpus, not an
 *       oversight);</li>
 *   <li>the synonyms from {@link CommandIntentSynonyms#intentToCommand()}
 *       whose target is this command (e.g. {@code mute}, {@code block},
 *       {@code hide} → {@code unfollow-source}).</li>
 * </ol>
 * The intent document is a matching surface only — its text is the
 * probe input to pgvector; the {@code HelpLookupTool} composes the
 * matched command's <em>description</em> at call time from the runtime
 * catalogue, never from the indexed text (the match-not-assert
 * invariant pinned by {@code HelpLookupToolTest}).
 *
 * <p><b>Content-hash skip.</b> Each row stores a SHA-256 of its source
 * text plus the active {@code infochat.embeddings.model} identifier.
 * The startup preflight diffs the in-memory source set against the
 * stored rows: a row whose hash AND model both match is skipped, so a
 * restart with an unchanged corpus performs zero embedding calls
 * (acceptance: {@code restartWithUnchangedCorpusPerformsNoEmbeddingCall}).
 * A change to the source text OR the model forces a re-embed of the
 * affected rows (acceptance: {@code changedIntentTextIsReEmbedded}) — a
 * stale vector can never outlive its source text.
 *
 * <p><b>Upsert shape.</b> DELETE-then-INSERT in one transaction, never
 * an UPDATE — the {@code doc_embedding} grants withhold UPDATE from the
 * provider role (V60; the narrow-grant posture the M1-648 r2 CLEAN
 * audit verified). Pruned rows (catalogue command removed) are DELETEd
 * in the same pass.
 *
 * <p><b>Failure posture — degrade, don't abort.</b> A failure in
 * {@link EmbeddingProvider#embed} or in the DAO writes is caught by
 * {@link #onStart} and logged at ERROR; the corpus is left empty (cold
 * start) or at its previous-boot state (warm restart). This is the
 * threat-model posture: {@code docs/spec/security.md} §Failure
 * handling binds every LLM-tier failure to a <em>degrade</em> path,
 * never a service-stop, because "a complete LLM outage degrades
 * quality, not safety." The chat-time {@code HelpLookupTool} already
 * implements the matching degrade behaviour — an empty corpus yields
 * {@code {"command":null}} and the agent says it does not know and
 * points at {@code /help} — so an embedding-backend failure at boot
 * costs helpLookup quality (free-text command suggestion is
 * unavailable) without touching any security control the Provider
 * enforces (ban intake, admin authorization, invite gate, digests,
 * every deterministic command). Aborting Provider startup here would
 * convert a chat-tier convenience outage into a total outage of every
 * security control the Provider carries — exactly the blast-radius
 * escalation the spec forbids. The operator-visible signal is the
 * ERROR log line; helpLookup self-heals on the next restart once the
 * embedding backend is healthy.
 */
@ApplicationScoped
public class CommandIntentIndexBuilder {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(CommandIntentIndexBuilder.class);

    private final DocEmbeddingDao dao;
    private final EmbeddingProvider embeddingProvider;
    private final BundleLoader bundleLoader;
    private final String embeddingModel;

    @Inject
    public CommandIntentIndexBuilder(DocEmbeddingDao dao,
                                     EmbeddingProvider embeddingProvider,
                                     BundleLoader bundleLoader,
                                     @ConfigProperty(name = "infochat.embeddings.model")
                                     String embeddingModel) {
        this.dao = dao;
        this.embeddingProvider = embeddingProvider;
        this.bundleLoader = bundleLoader;
        this.embeddingModel = embeddingModel;
    }

    /**
     * Build (or refresh) the command-intent corpus at Provider startup.
     * Pinned at {@link Priority} {@code 150} so this observer fires
     * AFTER Flyway (priority 100, runs V60) and AFTER any
     * {@code EmbeddingMetadataStartupGuard}-shaped data-integrity
     * precondition, and BEFORE the default-priority
     * {@code @Observes StartupEvent} observers (priority 1000). The
     * explicit pin removes the prior reliance on default observer
     * ordering — any future observer the builder's invariants depend
     * on can be pinned at a smaller priority to fire first.
     *
     * <p>Failures from {@link #buildCorpus} are caught and logged at
     * ERROR; Provider startup continues with an empty or stale corpus.
     * See the class javadoc's failure-posture section for the
     * threat-model rationale.
     */
    void onStart(@Observes @Priority(150) StartupEvent event) {
        log.debug("building {} corpus", CommandIntentIndex.DOC_KIND);
        long t0 = System.nanoTime();
        try {
            buildCorpus(t0);
        } catch (RuntimeException e) {
            // Degrade, don't abort — see the class javadoc's failure-posture
            // section. The chat-time HelpLookupTool returns {"command":null}
            // on an empty/partial corpus; helpLookup self-heals on next
            // restart once the embedding backend is healthy.
            log.error(
                    "CommandIntentIndexBuilder: failed building {} corpus — helpLookup "
                            + "will return no-match (or stale matches from a prior boot) "
                            + "at chat time. Provider startup continues; the chat path's "
                            + "friendly-degradation posture returns {{\"command\":null}} when "
                            + "no match is found, so users are directed to /help rather than "
                            + "getting confidently-wrong suggestions. Cause: {}",
                    CommandIntentIndex.DOC_KIND, e.toString(), e);
        }
    }

    /**
     * Build the corpus. Extracted from {@link #onStart} so the failure
     * posture (catch + log + degrade) wraps the whole body uniformly.
     * Any {@link RuntimeException} thrown here is caught by
     * {@link #onStart}; the two explicit {@code IllegalStateException}s
     * below (embed call failure, count mismatch) carry precise
     * diagnostic context into the operator-visible ERROR log without
     * the catch needing to discriminate.
     */
    private void buildCorpus(long t0) {
        // 1. Compose the in-memory source set from the runtime catalogue.
        Map<String, IntentDoc> sourceById = composeSourceSet();

        // 2. Read the stored rows (content-hash + model only — the
        //    embedding vector is never read here; the tool reads it via
        //    pgvector at lookup time).
        Map<String, DocEmbeddingDao.StoredRow> storedById =
                dao.selectExistingForKind(CommandIntentIndex.DOC_KIND);

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

        // 4. Prune disappeared rows (catalogue command removed since the
        //    last boot).
        if (!docIdsToDelete.isEmpty()) {
            log.info("pruning {} stale {} rows: {}", docIdsToDelete.size(),
                    CommandIntentIndex.DOC_KIND, docIdsToDelete);
            dao.delete(CommandIntentIndex.DOC_KIND, docIdsToDelete);
        }

        // 5. Embed the delta in ONE batch call (the SPI contract: one
        //    embed() per batch; the impl chunks on the wire). The empty
        //    batch short-circuits before the backend is touched, which
        //    is the content-hash skip.
        if (docIdsToEmbed.isEmpty()) {
            log.debug("{} corpus unchanged — zero embedding calls (content-hash skip)",
                    CommandIntentIndex.DOC_KIND);
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
                    "CommandIntentIndexBuilder: embedding backend failed building "
                            + CommandIntentIndex.DOC_KIND + " corpus (" + textsToEmbed.size()
                            + " rows) — refusing startup with a partial index", e);
        }
        if (embeddings.size() != textsToEmbed.size()) {
            throw new IllegalStateException(
                    "CommandIntentIndexBuilder: embedding backend returned "
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
        dao.upsert(CommandIntentIndex.DOC_KIND, rows);

        log.info("built {} corpus: {} embedded, {} skipped, {} pruned in {}ms",
                CommandIntentIndex.DOC_KIND,
                docIdsToEmbed.size(),
                sourceById.size() - docIdsToEmbed.size(),
                docIdsToDelete.size(),
                (System.nanoTime() - t0) / 1_000_000);
    }

    /**
     * Compose the in-memory source set: one {@link IntentDoc} per
     * catalogue command. The document text is intentionally short (one
     * sentence + the synonym list) — the matching surface, not a help
     * body (the second out-of-scope entry: full usage/example bodies
     * never enter the model context).
     */
    private Map<String, IntentDoc> composeSourceSet() {
        // synonymIndex: command name → list of intent words that resolve to it.
        Map<String, List<String>> synonymsByCommand = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : CommandIntentSynonyms.intentToCommand().entrySet()) {
            synonymsByCommand.computeIfAbsent(e.getValue(), k -> new ArrayList<>())
                    .add(e.getKey());
        }

        Map<String, IntentDoc> out = new LinkedHashMap<>();
        for (CommandHelp entry : HelpCommandHandler.CATALOGUE) {
            String command = entry.command();
            String shortHelp = bundleLoader.get(entry.bundleKey());
            List<String> synonyms = synonymsByCommand.getOrDefault(command, List.of());
            String text = composeIntentText(command, shortHelp, synonyms);
            String hash = sha256(text);
            // doc_id and target_ref are both the command name in v1
            // (CommandIntentIndex's doc_id-vs-target_ref split is a
            // forward-compat affordance for a future corpus whose doc_id
            // differs from its target_ref — see V60's doc comment).
            out.put(command, new IntentDoc(command, command, hash, text));
        }
        return out;
    }

    /**
     * Compose the intent document text for one command. Stable across
     * restarts (deterministic ordering of synonyms; deterministic
     * header), so the content-hash skip is itself deterministic.
     */
    private static String composeIntentText(String command, String shortHelp,
                                            List<String> synonyms) {
        StringBuilder sb = new StringBuilder();
        sb.append(command).append(": ").append(shortHelp);
        if (!synonyms.isEmpty()) {
            sb.append(". Intent words: ");
            for (int i = 0; i < synonyms.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(synonyms.get(i));
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
            // SHA-256 is mandated by the JLS spec for every JDK; this is
            // unreachable but rethrown loudly so a future security
            // provider regression fails startup rather than silently
            // producing a degenerate corpus.
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** In-memory source document, pre-embedding. */
    private record IntentDoc(String docId, String targetRef,
                             String contentHash, String text) {}
}
