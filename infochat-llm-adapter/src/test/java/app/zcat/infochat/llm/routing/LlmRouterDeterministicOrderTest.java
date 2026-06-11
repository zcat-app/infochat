package app.zcat.infochat.llm.routing;

import app.zcat.infochat.llm.LlmProvider;
import app.zcat.infochat.llm.LlmResponse;
import app.zcat.infochat.llm.ModelTask;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.util.TypeLiteral;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;
import org.junit.jupiter.api.Test;

import java.lang.annotation.Annotation;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * U-26: {@link LlmRouter#buildFromCdi} sorts the discovered providers by
 * name, so routing is independent of CDI bean-discovery order — which is not
 * stable across the Collector and the Provider, or across restarts. Without
 * the sort, the priority-3 {@code entries.get(0)} fallback (and the
 * priority-2 language first-match) would route differently depending on which
 * bean ArC happened to list first.
 *
 * <p>The test builds the router through the production {@code @Inject}
 * constructor (the path that runs {@code buildFromCdi}) twice with the same
 * two providers presented in reversed order, and asserts both route an
 * override-less task to the same provider — the alphabetically-first one.
 */
class LlmRouterDeterministicOrderTest {

    @Test
    void buildFromCdiSortsProvidersSoFallbackRoutingIsOrderIndependent() {
        NamedStubProvider aaa = new NamedStubProvider("aaa-provider");
        NamedStubProvider zzz = new NamedStubProvider("zzz-provider");

        // No infochat.llm.default.provider set → the implicit default
        // ("openai-compatible") matches neither stub, so forTask's priority-3
        // falls back to entries.get(0). With the sort, that is the
        // alphabetically-first provider regardless of discovery order.
        LlmRouter forwardOrder = new LlmRouter(new ListInstance(aaa, zzz), new EmptyConfig());
        LlmRouter reversedOrder = new LlmRouter(new ListInstance(zzz, aaa), new EmptyConfig());

        String forwardName = forwardOrder.forTask(ModelTask.SECURITY_JUDGE, "en").providerName();
        String reversedName = reversedOrder.forTask(ModelTask.SECURITY_JUDGE, "en").providerName();

        assertEquals(forwardName, reversedName,
            "routing must be identical regardless of CDI discovery order");
        assertEquals("aaa-provider", forwardName,
            "the order-independent fallback must be the alphabetically-first provider");
    }

    /** Stub provider that reports a fixed name; never invoked for generation. */
    private static final class NamedStubProvider implements LlmProvider {
        private final String name;

        NamedStubProvider(String name) {
            this.name = name;
        }

        @Override
        public String providerName() {
            return name;
        }

        @Override
        public LlmResponse generate(ModelTask task, String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException(
                "NamedStubProvider.generate must not be invoked by routing-order tests");
        }
    }

    /**
     * Minimal {@link Instance} backed by a fixed list — {@code buildFromCdi}
     * only iterates the instance. Mirrors the provider module's
     * {@code SingletonInstance} pattern; the unused CDI accessors throw so a
     * future change that starts consuming them fails loudly.
     */
    private static final class ListInstance implements Instance<LlmProvider> {
        private final List<LlmProvider> items;

        ListInstance(LlmProvider... items) {
            this.items = List.of(items);
        }

        @Override
        public Iterator<LlmProvider> iterator() {
            return items.iterator();
        }

        @Override
        public LlmProvider get() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instance<LlmProvider> select(Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends LlmProvider> Instance<U> select(Class<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <U extends LlmProvider> Instance<U> select(TypeLiteral<U> subtype, Annotation... qualifiers) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isUnsatisfied() {
            return items.isEmpty();
        }

        @Override
        public boolean isAmbiguous() {
            return items.size() > 1;
        }

        @Override
        public void destroy(LlmProvider instance) {
            // no-op
        }

        @Override
        public Handle<LlmProvider> getHandle() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<? extends Handle<LlmProvider>> handles() {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * Map-free {@link Config} stub: every optional lookup is empty, so each
     * provider defaults to English-only and no default-provider override is
     * seen. Only {@link #getOptionalValue(String, Class)} is exercised by the
     * router's construction path; the rest throw.
     */
    private static final class EmptyConfig implements Config {
        @Override
        public <T> T getValue(String propertyName, Class<T> propertyType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ConfigValue getConfigValue(String propertyName) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
            return Optional.empty();
        }

        @Override
        public <T> List<T> getValues(String propertyName, Class<T> propertyType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public <T> Optional<List<T>> getOptionalValues(String propertyName, Class<T> propertyType) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Iterable<String> getPropertyNames() {
            return List.of();
        }

        @Override
        public Iterable<ConfigSource> getConfigSources() {
            return List.of();
        }

        @Override
        public <T> Optional<Converter<T>> getConverter(Class<T> forType) {
            return Optional.empty();
        }

        @Override
        public <T> T unwrap(Class<T> type) {
            throw new UnsupportedOperationException();
        }
    }
}
