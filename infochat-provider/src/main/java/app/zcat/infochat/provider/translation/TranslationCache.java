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

/**
 * 24-hour in-memory translation cache per
 * {@code docs/design/05-llm-and-embeddings.md} §5.6. The cache key
 * is {@code (SHA-256(post-sanitizer-1 English text), lowercase
 * target_language)} so two callers whose pre-sanitizer LLM outputs
 * differ trivially (e.g. one carried an admin-verb fragment the
 * sanitizer stripped) collide on the same key after sanitization.
 *
 * <p>The cached value is the <strong>post-sanitizer-2 translated
 * text</strong>: a cache hit short-circuits both the translator
 * call AND the sanitizer-2 pass (spec §Pipeline order lines
 * 263-264).
 *
 * <p>{@code maximumSize(10_000)} is a memory-safety belt, not a
 * spec commitment. At ~2 KB per translated summary the worst case
 * is ~20 MB heap, well within any v1 deployment profile.
 */
@ApplicationScoped
public class TranslationCache {

    /**
     * Cache key: hex-encoded SHA-256 of the post-sanitizer-1 English
     * text + the lowercase ISO 639-1 target language code. Using the
     * hex form (not raw byte[]) ensures stable equals/hashCode across
     * JVMs and easy assertion in tests.
     */
    public record TranslationKey(String sha256Hex, String toLang) {}

    private final Cache<TranslationKey, String> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(24))
            .maximumSize(10_000)
            .build();

    /**
     * Look up a previously cached translation.
     *
     * @param englishText  post-sanitizer-1 English text (the SHA-256
     *                     source); never null.
     * @param toLang       ISO 639-1 target language code; never null.
     * @return the cached post-sanitizer-2 translated text, or empty
     *         on a miss.
     */
    public Optional<String> get(String englishText,
                                         String toLang) {
        TranslationKey key = keyFor(englishText, toLang);
        return Optional.ofNullable(cache.getIfPresent(key));
    }

    /**
     * Store a translation result. The value MUST be the post-sanitizer-2
     * translated text — the caller ({@code TranslationPipeline}) runs
     * sanitizer-2 before invoking this method.
     *
     * @param englishText              post-sanitizer-1 English text;
     *                                 never null.
     * @param toLang                   ISO 639-1 target language code;
     *                                 never null.
     * @param sanitized2TranslatedValue post-sanitizer-2 translated
     *                                  text; never null.
     */
    public void put(String englishText,
                    String toLang,
                    String sanitized2TranslatedValue) {
        TranslationKey key = keyFor(englishText, toLang);
        cache.put(key, sanitized2TranslatedValue);
    }

    private static TranslationKey keyFor(String englishText, String toLang) {
        String sha256Hex = sha256(englishText);
        String normalizedLang = toLang.toLowerCase(Locale.ROOT);
        return new TranslationKey(sha256Hex, normalizedLang);
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
