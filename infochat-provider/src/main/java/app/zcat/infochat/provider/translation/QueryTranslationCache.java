package app.zcat.infochat.provider.translation;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * In-memory cache for the QUERY-translation leg of hybrid retrieval
 * (M1-746, D58 condition (b) CACHED). Keyed by {@code (scope_kind,
 * scope_id, SHA-256(source text), source language)} — the D58 (b)
 * contract applied per scope: within a scope, a repeated query reuses
 * the stored translation, so "same query -> same posts" holds by
 * construction rather than by model determinism. This is the load-bearing
 * determinism property (D19): a cache MISS must never be able to change
 * a result set — a miss here means "not yet translated", never
 * "translated differently".
 *
 * <p><strong>Scope-partitioned (redteam R2, 2026-08-03):</strong> the
 * key carries the calling scope, so no cross-scope cache state exists.
 * A translation produced from one scope's query can never be served to
 * another scope's search, and cache hit/miss latency cannot be a
 * cross-scope oracle for another user's query text — the class of
 * sharing the presentation-path cache is granted only because its keys
 * are bot-authored prose, never user-authored content (security.md
 * §Prompt-injection defenses; query text is exactly the excluded
 * class). The per-scope cost is a re-translation when two scopes search
 * identical text — the price of the isolation, accepted.
 *
 * <p><strong>Hashed text key (redteam r4, 2026-08-03):</strong> the key
 * stores SHA-256 of the source text (hex), not the text itself — the
 * same decision the presentation-path {@link TranslationCache} makes —
 * so retained KEY memory is bounded (64 hex chars) and cannot scale
 * with an operator-raised {@code input-max-length}. Equality semantics
 * are unchanged: equal texts hash equal, and a SHA-256 collision on
 * query texts is cryptographically infeasible (the identical trade the
 * prose cache already accepts).
 *
 * <p>DELIBERATELY NOT the presentation-path {@link TranslationCache}:
 * that store keys {@code (SHA-256 of English prose, target language)}
 * — the opposite direction — and shares no class, no state and no key
 * space with this one (M1-746 notes: two caches, opposite directions,
 * do not merge them).
 */
@ApplicationScoped
public class QueryTranslationCache {

    /**
     * Cache key: the calling scope (kind + id), the hex SHA-256 of the
     * source text, and the lowercased ISO 639-1 source language code.
     * The hashed text is the D58 (b) contract — two spellings of "the
     * same" query are two different hashes, which is correct: they are
     * different texts. The scope component is the R2 partition: cache
     * state never crosses scopes.
     */
    public record QueryKey(String scopeKind, UUID scopeId, String sourceTextSha256Hex, String sourceLang) {}

    private final Cache<QueryKey, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(10_000)
            .build();

    /**
     * Look up a previously translated query.
     *
     * @param sourceText      the query text as the user typed it; never
     *                        null.
     * @param sourceLanguage  ISO 639-1 source language code from the
     *                        scope's declared {@code /lang}; never null.
     * @param scopeKind       the calling scope kind; never null.
     * @param scopeId         the calling scope id; never null.
     * @return the cached English-anchor translation, or empty on a miss.
     */
    public Optional<String> get(String sourceText, String sourceLanguage,
                                String scopeKind, UUID scopeId) {
        return Optional.ofNullable(cache.getIfPresent(keyFor(sourceText, sourceLanguage, scopeKind, scopeId)));
    }

    /**
     * Store a translation result. The value MUST be the provider's
     * translation verbatim — never post-processed, because any
     * transformation here would reintroduce exactly the "same query
     * could produce different text" nondeterminism the cache exists to
     * kill.
     *
     * @param sourceText      the query text as the user typed it; never
     *                        null.
     * @param sourceLanguage  ISO 639-1 source language code; never null.
     * @param scopeKind       the calling scope kind; never null.
     * @param scopeId         the calling scope id; never null.
     * @param translated      the English-anchor translation, verbatim;
     *                        never null.
     */
    public void put(String sourceText, String sourceLanguage,
                    String scopeKind, UUID scopeId, String translated) {
        cache.put(keyFor(sourceText, sourceLanguage, scopeKind, scopeId), translated);
    }

    private static QueryKey keyFor(String sourceText, String sourceLanguage,
                                   String scopeKind, UUID scopeId) {
        return new QueryKey(scopeKind, scopeId, sha256(sourceText),
                sourceLanguage.toLowerCase(Locale.ROOT));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is mandated by the JVM spec; this cannot happen.
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
