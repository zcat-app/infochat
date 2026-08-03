package app.zcat.infochat.provider.translation;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.TranslationProvider;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM-backed {@link TranslationProvider} that dispatches translation
 * requests through {@link LlmRouter#forTask(ModelTask, String)} with
 * {@link ModelTask#TRANSLATOR}. The prompt template wraps input text
 * in the spec's {@code <<<UNTRUSTED_CONTENT>>>} delimiter per
 * {@code docs/spec/llm.md} §Prompt-injection-aware prompt shape.
 *
 * <p>Returns the LLM's response body verbatim — the caller
 * ({@code TranslationPipeline}) is responsible for running
 * sanitizer-2 over the result.
 */
@ApplicationScoped
public class LlmTranslationProvider implements TranslationProvider {

    private static final Logger LOG = Logger.getLogger(LlmTranslationProvider.class);

    private static final String PROMPT_RESOURCE = "prompts/translator.md";

    /** A {@code {{SLOT}}} placeholder in {@link #PROMPT_RESOURCE}. */
    private static final Pattern PROMPT_SLOT = Pattern.compile("\\{\\{(\\w+)}}");

    @Inject
    LlmRouter llmRouter;

    // Loaded once in @PostConstruct loadPromptTemplate(); the field-init check
    // cannot see the @PostConstruct-time initialization.
    @SuppressWarnings("NullAway.Init")
    private String promptTemplate;

    @PostConstruct
    void loadPromptTemplate() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = LlmTranslationProvider.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(PROMPT_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException(
                        "LlmTranslationProvider: prompt resource not on classpath: "
                                + PROMPT_RESOURCE);
            }
            promptTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(
                    "LlmTranslationProvider: failed to load prompt resource "
                            + PROMPT_RESOURCE, e);
        }
    }

    @Override
    public String translate(String text,
                                     Locale from,
                                     Locale to) {
        if (from.equals(to)) {
            return text;
        }

        // Per-call random delimiter id prevents an attacker who seeded
        // the input from hard-coding a matching close marker.
        String delimiterUuid = UUID.randomUUID().toString();

        // SOURCE_LANGUAGE is the from-locale's English display name — for
        // the prose path (from=ENGLISH) the rendered prompt is
        // byte-identical to the pre-M1-747 "from English" literal; the
        // display-hit leg passes the post's declared source locale.
        String prompt = render(Map.of(
                "SOURCE_LANGUAGE", from.getDisplayLanguage(Locale.ENGLISH),
                "TARGET_LANGUAGE", to.getDisplayLanguage(Locale.ENGLISH),
                "id", delimiterUuid,
                "content", text));

        LlmProvider provider = llmRouter.forTask(ModelTask.TRANSLATOR, to.getLanguage());
        LlmResponse response = provider.generate(ModelTask.TRANSLATOR, "", prompt);
        return response.text();
    }

    /**
     * Fill every {@code {{SLOT}}} in the prompt template in a SINGLE scan.
     *
     * <p>Single-pass is a security property, not a style choice. A chain of
     * {@code String.replace} calls re-scans the text each substituted value
     * has already been written into, so only the LAST slot's value is safe
     * from expansion; every earlier slot can smuggle another slot's marker.
     * That is why {@code {{content}}} used to run last — and why
     * {@code {{SOURCE_LANGUAGE}}}, filled from the unvalidated
     * {@code source.language} column and substituted first, could expand
     * {@code {{id}}} into the per-call random delimiter and forge the close
     * marker the wrapper's forgery-proofness rests on. Matching over the
     * ORIGINAL template and appending into a separate buffer means no
     * substituted value is ever re-read, so the guarantee holds for every
     * slot at once and survives a future slot being added in any position.
     * ({@code TranslationPipeline} still gates the source language to an
     * ISO-639 shape; that is defense in depth, not the barrier.)
     * (Redteam 2026-08-03 round 2, low/INJECTION.)
     *
     * <p>Byte-compatible with the {@code replace} chain it replaces:
     * values are quoted so a {@code $} or {@code \} inside a headline stays
     * literal, and an unrecognized slot is left verbatim — exactly what a
     * chain with no matching call did.
     */
    private String render(Map<String, String> slotValues) {
        Matcher matcher = PROMPT_SLOT.matcher(promptTemplate);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String value = slotValues.get(matcher.group(1));
            matcher.appendReplacement(rendered,
                    Matcher.quoteReplacement(value == null ? matcher.group() : value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }
}
