package app.zcat.infochat.provider.testsupport;

import app.zcat.infochat.messaging.TranslationProvider;
import app.zcat.infochat.provider.bundle.BundleLoader;
import app.zcat.infochat.provider.translation.TranslationCache;
import app.zcat.infochat.provider.translation.TranslationPipeline;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shared reflective fixtures for the i18n bundle + translation surface, used
 * by the command/digest unit tests that construct these collaborators by hand
 * (no CDI). Both fixtures reach package-private internals through reflection
 * because the production wiring (a startup observer for the bundle, injected
 * fields for the pipeline) is not exercised in a plain unit test.
 */
public final class TranslationFixtures {

    private TranslationFixtures() {
    }

    /**
     * A {@link TranslationPipeline} wired for English short-circuit: an
     * identity {@link TranslationProvider} (returns its input untranslated), a
     * fresh {@link TranslationCache}, and the no-audit sanitizer. The pipeline's
     * collaborators are injected fields with no test constructor, so they are
     * set reflectively.
     */
    public static TranslationPipeline newEnShortCircuitPipeline() throws Exception {
        TranslationPipeline pipeline = new TranslationPipeline();
        Field cacheField = TranslationPipeline.class.getDeclaredField("translationCache");
        cacheField.setAccessible(true);
        cacheField.set(pipeline, new TranslationCache());

        Field providerField = TranslationPipeline.class.getDeclaredField("translationProvider");
        providerField.setAccessible(true);
        providerField.set(pipeline, (TranslationProvider) (text, from, to) -> text);

        Field sanitizerField = TranslationPipeline.class.getDeclaredField("llmOutputSanitizer");
        sanitizerField.setAccessible(true);
        sanitizerField.set(pipeline, SanitizerTestDoubles.noAuditSanitizer());
        return pipeline;
    }

    /**
     * A {@link BundleLoader} with its private {@code load()} invoked so the
     * en/cs message bundles are populated — the loader is normally driven by a
     * startup observer the unit tests do not run.
     */
    public static BundleLoader newRealBundleLoader() throws Exception {
        BundleLoader loader = new BundleLoader();
        Method load = BundleLoader.class.getDeclaredMethod("load");
        load.setAccessible(true);
        load.invoke(loader);
        return loader;
    }
}
