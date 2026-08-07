package app.zcat.infochat.provider.render;

import app.zcat.infochat.core.ingest.IngestTextNormalizer;
import app.zcat.infochat.provider.llm.LlmOutputSanitizer;
import app.zcat.infochat.provider.summary.EligiblePostQuery.Post;
import org.jspecify.annotations.Nullable;

import java.util.regex.Pattern;

/**
 * Derives the bounded one-line headline that labels a post in the four
 * user-visible render surfaces ({@code ClusterBlockRenderer}, {@code
 * SummaryProseGenerator.degradedProseFor}, {@code DegradedDigestRenderer},
 * {@code SavedCommandHandler}). Sharing one derivation is what keeps those
 * four from drifting.
 *
 * <p><b>Prompt input: summarizer no, roll-up yes.</b> The summarizer prompt
 * ({@code SummaryProseGenerator.buildPrompt}) must keep appending the FULL
 * untruncated title — it summarizes ONE cluster, so a bounded headline
 * would have the model describe a fragment, which produces no error and
 * no failing test. The roll-up prompt
 * ({@code CategoryRollupGenerator.buildPrompt}) is the deliberate
 * exception (M1-728, revising M1-714's original both-prompts rule): it
 * sees every cluster in a category and is told not to re-list them, so
 * it feeds each title through THIS helper — a corpus-maximum nitter
 * title contributes 200 chars + an ellipsis instead of crowding out
 * several hundred other titles.
 *
 * <p><b>Why a headline is derived at all.</b> {@code title} is a headline only
 * for RSS. For the social sources it is the post: measured over the 9,236-post
 * live corpus, a nitter title averages 334 characters (longest 24,776) and all
 * 729 Bluesky titles are empty. So the raw field is unbounded for one source
 * class and absent for another, and neither renders as a scannable anchor.
 *
 * <p><b>Order is load-bearing: flatten &rarr; sanitize &rarr; truncate.</b>
 * Flattening runs FIRST so the sanitizer inspects exactly the bytes that are
 * delivered. It cannot run after: {@link LlmOutputSanitizer}'s token separators
 * are the ASCII whitespace set only, and its canonical form (NFKC +
 * {@code stripBidiAndZeroWidth}) leaves U+0085, U+2028 and U+2029 intact, so
 * {@code /quarantine<U+2028>approve} reaches a post-sanitize rewrite as ONE
 * token — no closed-list match, no {@code LLM_OUTPUT_SANITIZED} row — and the
 * rewrite would then hand the group a dispatchable privileged command at a line
 * start. (Redteam 2026-07-30, medium/INJECTION.)
 * {@link LlmOutputSanitizer#sanitize} then runs over the WHOLE remaining field,
 * because every hit is audit-logged (rows aggregate per distinct token per
 * call, carrying the exact occurrence count) — truncating first would
 * silently drop the audit coverage for the removed tail and shrink what the
 * sanitizer sees. {@link #truncate} is therefore last.
 *
 * <p><b>Sanitize unit: ONE author's field per call (M1-697)</b> — or, on
 * the anchor-first path, one author's field PAIR (that field's stored text
 * plus its ingest translation; see {@link #derive}). The helper selects
 * title OR body and never concatenates the two, and never joins bytes from
 * two posts or two authors, because the flag-bearing closed-list entries
 * delete the span from command word to flag token: a widened input lets a
 * command word in one field and a flag in another erase everything between
 * them. The pair is the widest safe unit precisely because its flag-span
 * can reach nothing but the one post's own two rendered lines.
 */
public final class DisplayHeadline {

    /**
     * Display bound, in {@code char}s. 200 keeps essentially every RSS headline
     * whole (corpus average 74) while cutting the social sources down to a
     * scannable anchor (nitter average 334). A constant rather than a config
     * key on purpose: {@code ClusterBlockRenderer} is hand-constructed by two
     * command handlers, so a {@code @ConfigProperty} would have to be threaded
     * through both.
     */
    static final int MAX_LENGTH = 200;

    /** Appended only when something was actually cut. */
    static final String ELLIPSIS = "…";

    /**
     * Ceiling on how much of a body fallback reaches the sanitizer.
     * {@code post.title} is capped at {@code IngestTextNormalizer
     * .TITLE_MAX_LENGTH} (200) at the write boundary, but {@code post.body} has
     * no cap at any write path, and {@link LlmOutputSanitizer#sanitize} runs
     * NFKC, a markdown-link regex, 24 closed-list matchers and 10 tokenizer
     * scans over whatever it is handed — once per post, on the digest
     * scheduler thread. Bounding the body first restores the cost profile the
     * sanitizer's linearity argument assumes. (Redteam 2026-07-30, low/DOS.)
     *
     * <p>Generous relative to {@link #MAX_LENGTH} on purpose: this is a
     * runaway-input guard, not the display cut. At 10x the display bound, any
     * flagged span remotely near the visible region is still matched and still
     * audited, so the sanitize-then-truncate rule keeps governing what the
     * reader sees.
     *
     * <p>Public because a caller may need to bound the body BEFORE it reaches
     * Java at all. This guard runs after the column is materialised, which is
     * fine for one post but not for a paginated read: {@code /saved} lists 20
     * rows per page, so it repeats the bound in its {@code SELECT} and has to
     * name the same number here to keep the two from drifting. (M1-730,
     * redteam 2026-07-30 medium/DOS.)
     */
    public static final int BODY_SCAN_LIMIT = MAX_LENGTH * 10;

    private DisplayHeadline() {
    }

    /**
     * The post's display headline: its title when that has text, else its body,
     * sanitized, flattened to one line and bounded.
     *
     * @return the headline, or the empty string when the post carries no
     *         renderable text at all. Callers MUST then omit the headline
     *         token together with whatever separator would have followed it —
     *         no placeholder is invented here, because a stand-in like
     *         "(untitled)" conveys nothing the omission does not while adding
     *         an untranslated token to a line the scope's language governs.
     */
    public static String of(Post post, LlmOutputSanitizer llmOutputSanitizer) {
        // post.body() is nullable in the DDL (`body TEXT`) even though the
        // record's type is not marked, which is why it may cross into the
        // @Nullable parameter below. 728 of the 729 empty-title Bluesky posts
        // resolve through that body branch.
        return of(post.title(), post.body(), llmOutputSanitizer);
    }

    /**
     * The same headline for a caller holding a title/body pair rather than an
     * {@link Post} — {@code /saved} renders its own {@code saved_post}
     * snapshot content columns and never re-resolves CONTENT against
     * {@code post} (only the row's visibility consults {@code post.status},
     * inside the handler's SELECT), so it has no {@link Post} to hand over.
     * Both entry points run the identical derivation, which is the whole
     * point of sharing this class: {@code /saved} cannot drift from the
     * three surfaces M1-729 fixed. (M1-730.)
     *
     * @param body the snapshot body, nullable — {@code saved_post.body} and
     *             {@code post.body} are both nullable in the DDL
     * @return as {@link #of(Post, LlmOutputSanitizer)}: the empty string when
     *         neither field carries renderable text, and the caller must then
     *         omit the headline token together with its separator
     */
    public static String of(String title, @Nullable String body,
                            LlmOutputSanitizer llmOutputSanitizer) {
        String source = headlineSource(title, body);
        if (source.isEmpty()) {
            return "";
        }
        return truncate(llmOutputSanitizer.sanitize(flattenToOneLine(source)));
    }

    /**
     * Title when it carries text, else body, else empty. A blank field is
     * skipped rather than sanitized: a closed-list entry always begins with
     * {@code /}, so whitespace alone can never produce a hit, and skipping it
     * therefore costs no audit row.
     *
     * <p>{@link IngestTextNormalizer#UNTITLED_TITLE} counts as "no title"
     * alongside blank. The ingest write path substitutes that sentinel for a
     * titleless-by-design source to satisfy the {@code NOT NULL} column, so
     * matching only on blank would leave the fallback dead for every row
     * written since M1-693 — the sentinel is not blank. Exact equality, not a
     * contains or case-insensitive test: only the byte-exact value is the
     * storage placeholder, and a real title that merely mentions the word must
     * still render as itself. (M1-729.)
     */
    private static String headlineSource(String title, @Nullable String body) {
        if (titleIsRenderable(title)) {
            return title;
        }
        if (body == null || body.isBlank()) {
            return "";
        }
        // Unlike the title, the body has no write-boundary length cap, so it is
        // bounded here BEFORE the sanitizer is handed it. See BODY_SCAN_LIMIT.
        return boundForScan(body);
    }

    /**
     * The title-vs-body field choice, as a predicate, so the two entry
     * points cannot drift on it. Extracted for {@link #anchorFirst}, which
     * must make the SAME choice against the ORIGINAL pair and then take
     * that field's anchor — see that method for why choosing against the
     * anchor instead would resurrect a dead headline. (M1-729 states the
     * sentinel rule; M1-759 gave it a second reader.)
     */
    private static boolean titleIsRenderable(String title) {
        return !title.isBlank() && !IngestTextNormalizer.UNTITLED_TITLE.equals(title);
    }

    /**
     * The two lines of an anchor-first headline block (D29, amended
     * 2026-08-04): the text a reader of the corpus anchor language sees,
     * and the publisher's own words.
     *
     * @param readerLine   the English anchor when one exists, else the
     *                     original — so a NULL anchor degrades to the
     *                     original rather than to an empty headline, the
     *                     {@code coalesce(title_en, title)} shape
     *                     {@code EmbeddingWorker} already reads by
     * @param originalLine the publisher's own words, always
     * @param anchored     whether {@code readerLine} came from the anchor —
     *                     PROVENANCE only, deliberately not "and it is
     *                     therefore a translation". Whether the anchor may
     *                     be PRESENTED as the reader's language is a
     *                     second question, answered by
     *                     {@link #usesAnchor}: an anchor that displays as
     *                     the original is not evidence of translation, and
     *                     M1-771 makes that case degrade to the bracketed
     *                     shape rather than trusting this flag alone
     */
    public record AnchoredHeadline(String readerLine, String originalLine, boolean anchored) {

        /** Neither field carried renderable text; the caller omits the whole block. */
        public boolean isEmpty() {
            return readerLine.isEmpty();
        }
    }

    /** As {@link #anchorFirst(String, String, String, String, LlmOutputSanitizer)}, for a projected post. */
    public static AnchoredHeadline anchorFirst(Post post, LlmOutputSanitizer llmOutputSanitizer) {
        return anchorFirst(post.title(), post.body(), post.titleEn(), post.bodyEn(),
                llmOutputSanitizer);
    }

    /**
     * Derive the anchor-first block's two lines, each through the same
     * bound &rarr; flatten &rarr; sanitize &rarr; truncate order
     * {@link #of(String, String, LlmOutputSanitizer)} applies — the anchor
     * enters at the point the original does, never wrapped around an
     * already-sanitized value.
     *
     * <p><b>The field is chosen from the ORIGINAL, then that field's
     * anchor is taken.</b> Choosing from the anchor instead would
     * resurrect a headline M1-729 killed: {@code IngestTranslationWorker}
     * has no sentinel guard — it skips only {@code source.language='en'}
     * and requires a non-empty translated title — so a titleless
     * non-English post carries {@code title = }
     * {@link IngestTextNormalizer#UNTITLED_TITLE} and a {@code title_en}
     * that is a TRANSLATION of that sentinel. A translated sentinel is not
     * byte-equal to the sentinel, so {@link #titleIsRenderable} would pass
     * it through and the body fallback would never fire.
     *
     * <p><b>Sanitize unit: ONE author's field PAIR per call</b> (M1-697,
     * widened by the 2026-08-05 redteam). The two lines are two
     * derivations of the SAME publisher's field — its stored text and its
     * ingest translation — and they take ONE {@link
     * LlmOutputSanitizer#sanitize} call together, joined by a
     * renderer-authored newline. Per-line calls left a flag-bearing
     * closed-list entry able to straddle the pair unredacted and
     * unaudited. Widening this far and no further is the point: the join
     * never spans two POSTS or two AUTHORS, so the flag-span deletion can
     * only reach this post's own two lines and never erases another
     * publisher's bytes. See {@link #derive}.
     *
     * @param titleEn the English anchor for the title, or null when the
     *                ingest translator has not written one (or gave up)
     * @param bodyEn  the English anchor for the body, same contract.
     *                Bounded at {@link #BODY_SCAN_LIMIT} like every other
     *                operand with no write-boundary cap — the anchor
     *                columns are LLM-authored and capped nowhere
     */
    public static AnchoredHeadline anchorFirst(String title, @Nullable String body,
                                               @Nullable String titleEn, @Nullable String bodyEn,
                                               LlmOutputSanitizer llmOutputSanitizer) {
        if (titleIsRenderable(title)) {
            return derive(title, titleEn, llmOutputSanitizer);
        }
        if (body == null || body.isBlank()) {
            return new AnchoredHeadline("", "", false);
        }
        return derive(body, bodyEn, llmOutputSanitizer);
    }

    /**
     * Both lines of one chosen field, through ONE sanitize call over the
     * PAIR.
     *
     * <p><b>The unit is the field pair, not the line</b> (redteam
     * 2026-08-05, medium/INJECTION). Sanitizing the two lines
     * independently left a split a flag-bearing closed-list entry could
     * ride: the command word in {@code title_en} and the flag in
     * {@code title} match neither call, so the pair rendered adjacent in
     * one delivered message with no {@code [redacted command]} marker and
     * no {@code LLM_OUTPUT_SANITIZED} row. {@code docs/spec/security.md}
     * accepts exactly that residual ACROSS POSTS — but only because
     * merging THERE would let one publisher's flag-span delete another
     * publisher's bytes. Here both operands are one publisher's own field
     * and its ingest translation, so widening to the pair can only ever
     * consume that post's own two lines. M1-697's cross-post span bug
     * stays closed: the join never spans posts or authors.
     *
     * <p><b>The separator is a renderer-authored {@code \n}, and that is
     * what makes the split back out safe.</b> Both operands have already
     * been through {@link #flattenToOneLine}, which collapses every
     * whitespace run — including {@code \R} — to a single space, so no
     * feed byte can reproduce the newline. The sanitizer treats it as an
     * ordinary ASCII separator, which is precisely what lets a closed-list
     * entry match ACROSS it and be redacted and audited.
     *
     * <p>A one-line result means a whole-pair redaction (the survivor is
     * exactly the redaction marker) or a deleted line; the discriminator
     * is exact equality — see {@link #derive}.
     */
    private static AnchoredHeadline derive(String original, @Nullable String anchor,
                                           LlmOutputSanitizer llmOutputSanitizer) {
        if (anchor == null || anchor.isBlank()) {
            String originalLine = renderLine(original, llmOutputSanitizer);
            return new AnchoredHeadline(originalLine, originalLine, false);
        }
        // Reader line first, matching the delivered order, so a closed-list
        // entry reads to the sanitizer in the same direction it reads to a
        // copy-pasting admin: command word above, flag below.
        String sanitized = llmOutputSanitizer.sanitize(
                flattenToOneLine(boundForScan(anchor))
                        + '\n'
                        + flattenToOneLine(boundForScan(original)));
        String[] lines = sanitized.split("\n", 2);
        if (lines.length < 2) {
            String collapsed = truncate(sanitized);
            if (!collapsed.equals(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT)) {
                // Not exactly the marker: the survivor's provenance is
                // unrecoverable (a deleting pass may have dropped either
                // line), so the headline is omitted.
                return new AnchoredHeadline("", "", false);
            }
            // Exact equality, not contains: the marker literal is forgeable
            // from a prompt-injected anchor, but an exact forgery delivers
            // only the fixed literal. Spec: §LLM output sanitizer.
            return new AnchoredHeadline(collapsed, collapsed, false);
        }
        if (lines[0].isEmpty()) {
            // The anchor canonicalized away. An anchor of only zero-width
            // codepoints (U+200B/C/D, U+FEFF) passes the isBlank() guard above
            // — those are not whitespace, so neither isBlank() nor
            // flattenToOneLine's strip() removes them — and survives untouched
            // while nothing in the pair matches, because sanitize returns the
            // caller's own bytes on a no-match. The moment ANY closed-list
            // token matches elsewhere in the pair, sanitize returns the
            // CANONICAL form instead, in which stripBidiAndZeroWidth has
            // erased the anchor entirely. Degrade to the anchor-absent shape
            // rather than reporting an empty reader line: AnchoredHeadline
            // .isEmpty() keys off readerLine alone, so an empty one would make
            // every caller drop the surviving original with it and suppress the
            // whole headline. (Redteam 2026-08-05 round 2, out-of-model.)
            String originalLine = truncate(lines[1]);
            return new AnchoredHeadline(originalLine, originalLine, false);
        }
        return new AnchoredHeadline(truncate(lines[0]), truncate(lines[1]), true);
    }

    private static String renderLine(String source, LlmOutputSanitizer llmOutputSanitizer) {
        return truncate(llmOutputSanitizer.sanitize(flattenToOneLine(boundForScan(source))));
    }

    /**
     * Whether the English anchor is what this reader should see in the
     * primary slot. True only when an anchor exists, that anchor does not
     * still CARRY the publisher's own words in the publisher's order, AND
     * the reader does not already read the post's declared source language:
     * for a Czech reader of a Czech-source post the publisher's own words
     * ARE the reader-language line, and promoting the anchor would show
     * that reader English. D29's collapse is scoped to "a headline whose
     * source language differs from the reader's" for exactly this reason.
     *
     * <p>A NULL or non-ISO source language does NOT suppress the anchor:
     * unknown means "never translate", not "never anchor", and the anchor
     * is a column read either way.
     *
     * <p><b>The word-subsequence clause is the enforcement point for
     * D29 (c)</b> (M1-771). The bracket promises a reader that an
     * unbracketed line is in their language; the only thing standing
     * behind that is an anchor column written from LLM output, which
     * {@code docs/spec/security.md} §Trust boundaries item 9 declares
     * untrusted. When the anchor still carries every one of the
     * publisher's words in the publisher's order, that IS the evidence the
     * anchor is not a translation, so it must not be promoted: returning
     * false puts the original in the primary slot and lets
     * {@link app.zcat.infochat.provider.translation.TranslationPipeline#primaryInReaderLanguage}
     * bracket it — the same degraded shape D29 (c) already specifies for
     * an ABSENT anchor.
     *
     * <p><b>Why here and not at the ingest write.</b> The collector's echo
     * check ({@code IngestTranslationWorker}) sees the value it stores; it
     * cannot see the value a reader is SHOWN, which this class then
     * reduces by {@link #boundForScan}, {@link #flattenToOneLine},
     * {@link LlmOutputSanitizer#sanitize} and {@link #truncate}. Three
     * successive 2026-08-05 red-team rounds each named one more of those
     * reductions as an evasion, and mirroring them collector-side is not
     * merely a treadmill but wrong in principle — the 200-char display cut
     * would have to judge a full-length body by its first 200 characters.
     * Evaluated HERE the question needs no mirroring at all: both operands
     * are already the final strings, so every present and future reduction
     * is covered by construction.
     *
     * <p>False positives are accepted and are the safe direction: a
     * headline that legitimately translates to itself — a proper-noun
     * title — renders bracketed instead of bare. That costs one bracket
     * per render and is reversible; the same false positive at the ingest
     * write costs the anchor permanently.
     */
    public static boolean usesAnchor(AnchoredHeadline headline,
                                     @Nullable String sourceLanguage,
                                     String scopeLanguage) {
        return headline.anchored()
                && !displaysAsTheOriginal(headline.originalLine(), headline.readerLine())
                && !(sourceLanguage != null && sourceLanguage.equalsIgnoreCase(scopeLanguage));
    }

    /**
     * Whether {@code derived} still carries every one of {@code original}'s
     * words, in {@code original}'s order — i.e. whether the "translation"
     * is the text it was derived from with material merely inserted around
     * or between its words. See {@link #usesAnchor} for why the question is
     * asked at all.
     *
     * <p><b>ONE predicate, BOTH translation hops (M1-771).</b> A
     * non-English reader is translated twice — source to English at
     * ingest, English to their own language at display — so a check on the
     * anchor alone still leaves the second hop able to hand that reader
     * English beneath a line claiming their language. The display-hit leg
     * ({@code TranslationPipeline.runForDisplayHit}) therefore CALLS this
     * method on its own input/reply pair. Called, never copied: two
     * divergent copies of one check is exactly what M1-771's first three
     * red-team rounds were, and a copy drifts the moment either side is
     * tuned.
     *
     * <p><b>Word-subsequence rather than equality, and the difference is
     * the control.</b> Equality is defeated by adding ONE character, and
     * whether that character is caught then depends on {@link #displayForm}
     * happening to know it — which is a list, and a list can be stepped
     * around. This test does not need to know what the added character IS:
     * padding leaves every word intact and in order, and so does
     * substituting it for a space between two words. It subsumes equality
     * for any original with at least one visible word (a line trivially
     * contains its own words in order), so nothing the equality form
     * caught is lost. [M1-771 red-team 2026-08-05 round 4,
     * where U+2800 BRAILLE PATTERN BLANK — category So, so neither Cf nor
     * Mn, and blank rather than invisible — padded a verbatim echo past
     * the equality form.]
     *
     * <p>{@link #displayForm} still runs on both sides, because the two
     * catch DIFFERENT things: the word walk covers anything added between
     * or around words, the category strip covers an invisible code point
     * inserted INSIDE a word, which would otherwise break that word's
     * match. Their union is wider than either.
     *
     * <p><b>Where this deliberately stops — two places, both by decision
     * rather than oversight.</b>
     * <ul>
     *   <li>A character the strip does not know, inserted INSIDE a word,
     *       still evades. The next rung is a character-level subsequence
     *       walk and the rung after that a similarity score; neither is
     *       built, because each buys a narrower evasion class at a steeply
     *       worse false-positive rate and the failure they would catch
     *       renders as a visibly mangled word, which tells the reader what
     *       a bracket would.</li>
     *   <li>On a line longer than {@link #MAX_LENGTH}, ANY insertion AT
     *       OR BEFORE the cut evades WHEN THE CALLER'S OPERANDS ARRIVE
     *       TRUNCATED, which is {@link #usesAnchor}'s case — a leading pad
     *       is only its most obvious form. Material added ahead of the cut
     *       shifts it, so the anchor's tail is ALTERED rather than merely
     *       extended and the last word's match fails. Only an addition
     *       AFTER the cut is unaffected, because truncation discards it.
     *       Closing it for that caller
     *       would need a SECOND walk on the pre-cut strings, since the
     *       post-cut evaluation is exactly what catches a divergence
     *       BEYOND the cut (red-team round 3): the two cases want opposite
     *       evaluation points, and the pair was judged not worth it for a
     *       residual whose reader-facing outcome is identical to the one
     *       above. The display-hit caller does NOT carry this residual —
     *       it compares its reply before the cut. [red-team 2026-08-05
     *       round 5; recorded in D29 (c)]</li>
     * </ul>
     *
     * <p>False positives are accepted, and are the safe direction — a
     * genuine translation that happens to carry the original's words in
     * order (a two-letter original like {@code AI}, a numeric headline, or
     * a translator that quotes the source in parentheses) renders
     * bracketed instead of bare. One bracket per render, reversible.
     *
     * <p>A reduced original with NO words matches nothing: {@code from}
     * stays at zero, so a title composed entirely of dropped code points
     * cannot vacuously swallow every genuine translation on the post. Feed
     * text is untrusted, so that is a boundary case rather than a
     * hypothetical. It is also the ONE case where this does not subsume
     * byte equality — an all-invisible original is byte-equal to its own
     * echo and still answers false — so a caller for which the
     * byte-identical reply is a meaningful outcome tests equality itself
     * rather than relying on this.
     *
     * <p>Still NOT a language check — D29 refuses to infer a language from
     * text, so a fluent mistranslation, or an edit that CHANGES a word
     * rather than adding to it, passes here and remains the stated
     * residual it has always been.
     *
     * @param original the text the derivation started from — the
     *                 publisher's rendered line for the anchor hop, the
     *                 headline handed to the translator for the display hop
     * @param derived  what that channel returned for it
     */
    public static boolean displaysAsTheOriginal(String original, String derived) {
        String anchor = displayForm(derived);
        int from = 0;
        for (String word : displayForm(original).split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            int at = anchor.indexOf(word, from);
            if (at < 0) {
                return false;
            }
            from = at + word.length();
        }
        return from > 0;
    }

    /**
     * A line reduced to what a reader can actually perceive: invisible
     * code points dropped, by Unicode general CATEGORY rather than by
     * enumeration.
     *
     * <p>Both categories are load-bearing and neither subsumes the other.
     * Format (Cf) covers U+00AD SOFT HYPHEN, U+2060 WORD JOINER and the
     * zero-width set; nonspacing mark (Mn) covers U+034F COMBINING
     * GRAPHEME JOINER and the U+FE00..U+FE0F variation selectors, which
     * are NOT Cf — an earlier Cf-only form of this check was reported
     * closed while those still padded a verbatim echo past it. Naming
     * individual code points is what fails here; the category is the
     * property that matters.
     *
     * <p>Dropping Mn wholesale cannot merge two genuinely different
     * headlines: NFKC has already run, so ordinary accented Latin text is
     * PRECOMPOSED (U+00FA, category Ll) and untouched by this. What
     * remains as Mn is the marks with no composed form, and for those to
     * decide the comparison the two lines would have to agree on every
     * base character already — which is an echo, not a translation.
     *
     * <p>Comparison only. Nothing built here is rendered or stored.
     */
    private static String displayForm(String line) {
        StringBuilder visible = new StringBuilder(line.length());
        line.codePoints()
                .filter(codePoint -> {
                    int type = Character.getType(codePoint);
                    return type != Character.FORMAT && type != Character.NON_SPACING_MARK;
                })
                .forEach(visible::appendCodePoint);
        return visible.toString();
    }

    /**
     * Compose the block every surface in scope renders: the primary line,
     * then the publisher's own words bracketed beneath it.
     *
     * <p><b>The bracket is the invariant</b> (D29 (c)): an UNBRACKETED
     * line always means "this is already in your language". So the primary
     * line is bracketed whenever it is NOT known to be in the reader's
     * language — the anchor-absent case, and every path where the display
     * translation was skipped or failed. Leaving those bare is precisely
     * the indistinguishability the bracket exists to remove.
     *
     * <p>The subordinate line is SUPPRESSED when it would repeat the
     * primary, which is what keeps an already-in-the-reader's-language
     * post at one line and stops the anchor-absent case (where primary and
     * original are the same string) printing twice.
     *
     * <p>Bracket wrapping happens AFTER {@link #truncate} — deliberately
     * outside it — so a display cut can never drop the closing bracket.
     * The two added chars are allowed to exceed {@link #MAX_LENGTH}.
     *
     * <p>Both operands reach here already flattened, so no feed-authored
     * byte can introduce a line break: every newline in the returned block
     * is authored by this method. That is what makes it safe to turn a
     * one-line entry into a two-line one on a group broadcast surface.
     *
     * @param primaryLine            the line in the primary slot — the
     *                               anchor, a display translation, or the
     *                               original, per the surface
     * @param primaryInReaderLanguage whether {@code primaryLine} is known
     *                               to be in the reader's language
     * @param note                   the display-hit leg's
     *                               translation-unavailable note, or null.
     *                               Placed between the primary and the
     *                               subordinate line, and never bracketed:
     *                               it is bot-authored prose reporting
     *                               that a translation was ATTEMPTED AND
     *                               FAILED, which position cannot convey,
     *                               so it is not redundant with the
     *                               bracket
     */
    public static String anchorBlock(String primaryLine, boolean primaryInReaderLanguage,
                                     String originalLine, @Nullable String note) {
        StringBuilder block = new StringBuilder(primaryFor(primaryLine, primaryInReaderLanguage));
        if (note != null) {
            block.append('\n').append(note);
        }
        String subordinate = subordinateFor(primaryLine, originalLine);
        if (!subordinate.isEmpty()) {
            block.append('\n').append(subordinate);
        }
        return block.toString();
    }

    /**
     * The primary line alone, bracketed per {@link #anchorBlock}'s
     * invariant. Split out for {@code /saved}, whose line is a
     * {@code MessageFormat} template with the headline in a middle slot:
     * interpolating a whole two-line block there would leave the
     * "saved … tags:" metadata attached to the SUBORDINATE line instead of
     * the primary one. It threads the primary through the template and
     * appends {@link #subordinateFor} after the formatted line, which is
     * the same composition {@link #anchorBlock} performs — just around a
     * template rather than a bare line.
     */
    public static String primaryFor(String primaryLine, boolean primaryInReaderLanguage) {
        return primaryInReaderLanguage ? primaryLine : bracketed(primaryLine);
    }

    /**
     * The bracketed original beneath the primary, or the empty string when
     * it is suppressed for repeating the primary line.
     */
    public static String subordinateFor(String primaryLine, String originalLine) {
        return originalLine.isEmpty() || originalLine.equals(primaryLine)
                ? ""
                : bracketed(originalLine);
    }

    /**
     * Wrap a rendered line as the subordinate original. Punctuation, not
     * localized text — deliberately NOT a bundle key (D43 is unaffected;
     * the ticket that introduced this weighed a localized {@code originál:}
     * label and left it to a separate decision).
     *
     * <p><b>The wrap must not complete a system-marker impersonation.</b>
     * {@code docs/spec/security.md} commits two bracketed literals as
     * byte-identical so prose, snapshot bodies, tests and an operator
     * triaging output all recognise them by EXACT MATCH:
     * {@link LlmOutputSanitizer#REDACTED_COMMAND_REPLACEMENT} and the
     * Stage 1 {@code [REDACTED:<id>]} placeholder. Because this method
     * supplies the brackets around wholly publisher-controlled text, a
     * feed title of bare {@code redacted command} would otherwise render
     * byte-identical to a real redaction — for a post that was never
     * flagged and produced no {@code LLM_OUTPUT_SANITIZED} audit row, so
     * an operator correlating rendered markers against {@code audit_log}
     * sees a phantom. The spec names the per-row {@code <id>}
     * randomization as what stops a pre-crafted placeholder, and that
     * argument holds only while the attacker must supply the brackets too.
     *
     * <p>On collision the wrap inserts ONE renderer-authored space inside
     * EACH bracket. The break is unforgeable — every operand arrives
     * through {@link #flattenToOneLine}, whose {@code strip()} removes
     * leading and trailing whitespace, so no feed text can reproduce it —
     * while leaving the publisher's words readable, which matters because
     * they are the point of the subordinate line. Both brackets are broken
     * because either one can be the complicit half: a title ending
     * {@code x [redacted command} is completed by the CLOSING bracket just
     * as {@code redacted command] x} is completed by the opening one. After
     * the break neither literal can span a renderer bracket at all — one
     * would have to begin {@code [r} / {@code [R} at index 0 or end
     * {@code d]} / {@code <id>]} at the last index, and both positions now
     * hold a space.
     *
     * <p>Exact-match recognition of a genuine marker is unaffected: the
     * break only adds characters at the ends, so a REAL redaction survives
     * byte-exact wherever it sits — a redacted whole headline wraps as
     * {@code [[redacted command]]}, which does not collide.
     * (Redteam 2026-08-04, low/INJECTION; round 2 for the second bracket.)
     */
    public static String bracketed(String line) {
        String wrapped = "[" + line + "]";
        return wrapSynthesizedASystemMarker(wrapped) ? "[ " + line + " ]" : wrapped;
    }

    /**
     * Whether the wrap SYNTHESIZED one of the two bracketed literals
     * {@code docs/spec/security.md} commits — i.e. whether an occurrence in
     * {@code wrapped} is built from a bracket this class supplied rather
     * than from one the publisher wrote. Such an occurrence must start at
     * index 0 or end at the last index, because those are the only two
     * positions the renderer's brackets occupy; an occurrence strictly
     * inside was already in the feed text, which is the pre-existing
     * forgery class (a title carrying {@code [redacted command]} in full
     * renders those bytes on every surface today) that this wrap neither
     * creates nor is charged with closing.
     *
     * <p>Whole-string equality is NOT sufficient, and was the round-1 gap:
     * a title carrying its own {@code ]} pairs with the opening bracket and
     * leaves the literal as a SUBSTRING — {@code redacted command] x} wraps
     * to {@code [redacted command] x]}.
     *
     * <p>The Stage 1 placeholder carries a per-row random {@code <id>}, so
     * it is matched by SHAPE rather than by value — the point is that the
     * rendered bytes read as a placeholder to a reader, which does not
     * require guessing the id.
     */
    private static boolean wrapSynthesizedASystemMarker(String wrapped) {
        return wrapped.startsWith(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT)
                || wrapped.endsWith(LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT)
                || STAGE1_PLACEHOLDER_SHAPE.matcher(wrapped).lookingAt()
                || STAGE1_PLACEHOLDER_SHAPE_AT_END.matcher(wrapped).find();
    }

    /**
     * Bound an unbounded operand at {@link #BODY_SCAN_LIMIT} before it
     * reaches the sanitizer — the {@code BODY_SCAN_LIMIT} runaway-input
     * guard as a reusable cut (code-point-safe like {@link #truncate}'s).
     */
    private static String boundForScan(String text) {
        return text.length() <= BODY_SCAN_LIMIT
                ? text
                : text.substring(0, codePointSafeCut(text, BODY_SCAN_LIMIT));
    }

    /**
     * Prepare an LLM-authored headline replacement for a headline slot:
     * bound at {@link #BODY_SCAN_LIMIT}, flatten to one line, then
     * sanitize — the same order, and the same reasons, as
     * {@link #of(String, String, LlmOutputSanitizer)}. The display-hit
     * translation leg (M1-747) hands the translator's reply here; the
     * reply is LLM output bounded only by the provider's 1-8 MiB body cap,
     * so the pre-bound keeps a hostile endpoint's in-cap answer from
     * buying megabytes of NFKC and closed-list scanning.
     *
     * <p><b>This composite exists so the order cannot be gotten wrong.</b>
     * The three steps are private precisely because applying
     * {@code flattenToOneLine} AFTER {@link LlmOutputSanitizer#sanitize}
     * re-creates the line-boundary smuggling hazard documented on this
     * class — a finding twice over (2026-07-30 on the body operand;
     * 2026-08-03 round 1 on the display-hit cache-read path). A caller
     * that cannot reach the primitives cannot sequence them wrongly, which
     * is the structural standard {@code docs/spec/security.md} §"The
     * chokepoint routing is build-guarded" sets for this kind of
     * invariant. Truncation is deliberately NOT part of this composite:
     * the display-hit leg caches the sanitized form and cuts afterwards,
     * and {@link #truncate} can only drop a suffix, so it is safe to apply
     * on its own. (Redteam 2026-08-03 round 2, low/INJECTION.)
     *
     * @return the bounded, one-line, sanitized headline — NOT truncated to
     *         {@link #MAX_LENGTH}; the caller applies {@link #truncate}
     */
    public static String prepareTranslatedHeadline(String translated,
                                                   LlmOutputSanitizer llmOutputSanitizer) {
        return llmOutputSanitizer.sanitize(flattenToOneLine(boundForScan(translated)));
    }

    /**
     * Collapse every whitespace run to a single space so the headline cannot
     * inject extra lines into the block that contains it. {@code \R} is matched
     * alongside {@code \s} to catch the Unicode line boundaries {@code \s}
     * misses (U+0085, U+2028, U+2029) — a group-broadcast line start is exactly
     * where a smuggled break would do damage, and those three are also the code
     * points the sanitizer does not count as token separators, which is why
     * this runs BEFORE it rather than after.
     *
     * <p>Runs are REPLACED with a space, never deleted. Deletion would splice
     * {@code /list} and {@code -sources} into a {@code /list-sources} the author
     * never wrote; because this now runs ahead of the sanitizer that token
     * would be caught and redacted rather than leaked, but fabricating it in
     * the first place corrupts the headline and emits a spurious audit row.
     *
     * <p>PRIVATE, and load-bearingly so: reachable only through the two
     * entry points that sequence it before the sanitizer
     * ({@link #of(String, String, LlmOutputSanitizer)} and
     * {@link #prepareTranslatedHeadline}), so no caller can apply it to an
     * already-sanitized value.
     */
    private static String flattenToOneLine(String source) {
        return source.replaceAll("(?:\\R|\\s)+", " ").strip();
    }

    /**
     * Opening literal of the Stage 1 redaction placeholder
     * {@code [REDACTED:<id>]}. {@code docs/spec/security.md} §Ingest pipeline
     * commits the brackets and the {@code REDACTED:} literal as byte-identical
     * "so user-facing prose, snapshot bodies, and tests recognise the marker by
     * exact-match", so the display cut must not be able to break that shape.
     * The {@code <id>} token is per-row random, hence prefix-matching rather
     * than a whole-marker constant. Reachable because Stage 1 redactions live
     * in the body, which this helper can promote to a headline.
     * (Redteam 2026-07-30, out-of-model.)
     */
    private static final String STAGE1_REDACTION_PREFIX = "[REDACTED:";

    /**
     * {@code [REDACTED:<id>]} as a shape, for
     * {@link #wrapSynthesizedASystemMarker} — applied with
     * {@link java.util.regex.Matcher#lookingAt()} for an occurrence built
     * from the renderer's OPENING bracket, and in the {@code _AT_END} twin
     * (anchored with {@code \z}, which unlike {@code $} cannot also match
     * before a trailing line terminator) for one built from its CLOSING
     * bracket.
     *
     * <p>The id class excludes whitespace, which the genuine id never
     * carries — {@code PlaceholderIds} emits base32, canonical regex
     * {@code ^\[REDACTED:[A-Z2-7]{26}\]$}. That exclusion is what makes
     * {@link #bracketed}'s space break effective rather than cosmetic: with
     * whitespace admitted, a broken {@code [REDACTED:<id> ]} would still
     * satisfy this shape and the break would close nothing. It stays LOOSER
     * than the canonical regex on everything else on purpose — a reader
     * does not verify the id, so a wrong-shaped id still reads as a
     * redaction.
     *
     * <p>Both are declared AFTER {@link #STAGE1_REDACTION_PREFIX} on
     * purpose: static initializers run in declaration order, so building
     * these patterns above that constant would compile and then quote a
     * null at class-init.
     */
    private static final Pattern STAGE1_PLACEHOLDER_SHAPE =
            Pattern.compile(Pattern.quote(STAGE1_REDACTION_PREFIX) + "[^\\]\\s]*\\]");

    /** {@link #STAGE1_PLACEHOLDER_SHAPE} anchored at the end of input. */
    private static final Pattern STAGE1_PLACEHOLDER_SHAPE_AT_END =
            Pattern.compile(Pattern.quote(STAGE1_REDACTION_PREFIX) + "[^\\]\\s]*\\]\\z");

    /**
     * Bound the headline at {@link #MAX_LENGTH}, appending {@link #ELLIPSIS}
     * only when something was cut. Three boundaries are respected: a cut may
     * not land inside a {@link LlmOutputSanitizer#REDACTED_COMMAND_REPLACEMENT}
     * marker nor inside a {@link #STAGE1_REDACTION_PREFIX} placeholder (a
     * half-emitted {@code [redacted comm} or {@code [REDACTED:9f} reads as
     * content rather than as a redaction), and a cut may not split a surrogate
     * pair (287 of 1,868 nitter titles carry emoji, so an astral-plane
     * character at the boundary is routine).
     *
     * <p>Public because the display-hit translation leg (M1-747) re-bounds
     * the translated headline with this same cut — a translation of a
     * MAX_LENGTH input legitimately runs longer, and a second truncation
     * implementation would lose the marker-safety arguments above.
     */
    public static String truncate(String headline) {
        if (headline.length() <= MAX_LENGTH) {
            return headline;
        }
        int cut = MAX_LENGTH;
        cut = Math.min(cut, markerSafeCut(headline, cut,
                LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT,
                LlmOutputSanitizer.REDACTED_COMMAND_REPLACEMENT.length()));
        // The Stage 1 placeholder's own length is not fixed (per-row random
        // id), so the closing bracket AFTER the cut is what proves the cut
        // landed inside one.
        cut = Math.min(cut, stage1PlaceholderSafeCut(headline, cut));
        return headline.substring(0, codePointSafeCut(headline, cut))
                .stripTrailing() + ELLIPSIS;
    }

    /**
     * Pull {@code cut} back to the start of a {@code marker} occurrence that
     * straddles it, or leave it alone.
     */
    private static int markerSafeCut(String headline, int cut, String marker, int markerLength) {
        int start = headline.lastIndexOf(marker, cut - 1);
        return start >= 0 && start + markerLength > cut ? start : cut;
    }

    /**
     * Pull {@code cut} back to the start of a {@code [REDACTED:<id>]}
     * placeholder that straddles it. The placeholder is variable-length, so a
     * straddle is detected by an opener at or before the cut whose matching
     * {@code ]} falls at or after it.
     */
    private static int stage1PlaceholderSafeCut(String headline, int cut) {
        int start = headline.lastIndexOf(STAGE1_REDACTION_PREFIX, cut - 1);
        if (start < 0) {
            return cut;
        }
        int close = headline.indexOf(']', start + STAGE1_REDACTION_PREFIX.length());
        return close < 0 || close >= cut ? start : cut;
    }

    /** Back {@code cut} off by one when it would split a surrogate pair. */
    private static int codePointSafeCut(String text, int cut) {
        return cut > 0 && Character.isHighSurrogate(text.charAt(cut - 1)) ? cut - 1 : cut;
    }
}
