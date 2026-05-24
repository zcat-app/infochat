package app.zcat.infochat.llm.translation;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import app.zcat.infochat.llm.routing.LlmRouter;
import app.zcat.infochat.messaging.TranslationProvider;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.jspecify.annotations.NonNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

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

    @Inject
    LlmRouter llmRouter;

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
    public @NonNull String translate(@NonNull String text,
                                     @NonNull Locale from,
                                     @NonNull Locale to) {
        if (from.equals(to)) {
            return text;
        }

        // Per-call random delimiter id prevents an attacker who seeded
        // the input from hard-coding a matching close marker.
        String delimiterUuid = UUID.randomUUID().toString();

        // {{content}} MUST be replaced last: if it ran before {{id}},
        // attacker-injected "{{id}}" in the text would expand to the
        // real delimiter UUID, enabling a forged close marker.
        String prompt = promptTemplate
                .replace("{{TARGET_LANGUAGE}}", to.getDisplayLanguage(Locale.ENGLISH))
                .replace("{{id}}", delimiterUuid)
                .replace("{{content}}", text);

        LlmProvider provider = llmRouter.forTask(ModelTask.TRANSLATOR, to.getLanguage());
        LlmResponse response = provider.generate(ModelTask.TRANSLATOR, "", prompt);
        return response.text();
    }
}
