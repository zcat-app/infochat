package app.zcat.infochat.llm.impl;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigValue;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.Converter;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal map-backed {@link Config} stub shared by the {@code impl}-package
 * provider tests. Extracted from four byte-identical private copies that had
 * accreted across the chat/embedding provider tests (avoid-test-inner-classes
 * rule).
 *
 * <p>Implements only the slice of the {@link Config} surface the provider
 * tests exercise: {@link #getValue} / {@link #getOptionalValue} with
 * {@code String} / {@code Long} / {@code Integer} conversion. Every other
 * method throws {@link UnsupportedOperationException} so an unstubbed lookup
 * fails loudly rather than returning a misleading default.
 *
 * <p>Package-private and top-level. The {@code routing}-package
 * {@code LlmRouterTest} keeps its own copy: a package-private type here is not
 * visible from another package, and that test is owned by a separate ticket.
 */
final class StubConfig implements Config {
    private final Map<String, String> values;

    StubConfig(Map<String, String> values) {
        this.values = Map.copyOf(values);
    }

    @Override
    public <T> T getValue(String propertyName, Class<T> propertyType) {
        String raw = values.get(propertyName);
        if (raw == null) {
            throw new java.util.NoSuchElementException(
                "StubConfig: no value for " + propertyName);
        }
        return convert(raw, propertyType);
    }

    @Override
    public ConfigValue getConfigValue(String propertyName) {
        throw new UnsupportedOperationException("getConfigValue not stubbed");
    }

    @Override
    public <T> Optional<T> getOptionalValue(String propertyName, Class<T> propertyType) {
        String raw = values.get(propertyName);
        if (raw == null || raw.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(convert(raw, propertyType));
    }

    @Override
    public <T> List<T> getValues(String propertyName, Class<T> propertyType) {
        throw new UnsupportedOperationException("getValues not stubbed");
    }

    @Override
    public <T> Optional<List<T>> getOptionalValues(String propertyName, Class<T> propertyType) {
        throw new UnsupportedOperationException("getOptionalValues not stubbed");
    }

    @Override
    public Iterable<String> getPropertyNames() {
        return values.keySet();
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
        throw new UnsupportedOperationException("unwrap not stubbed");
    }

    private static <T> T convert(String raw, Class<T> type) {
        if (type == String.class) {
            return type.cast(raw);
        }
        if (type == Long.class || type == long.class) {
            return type.cast(Long.parseLong(raw));
        }
        if (type == Integer.class || type == int.class) {
            return type.cast(Integer.parseInt(raw));
        }
        throw new UnsupportedOperationException("StubConfig: unsupported type " + type);
    }
}
