package app.zcat.infochat.llm;


/**
 * Shared proxy-unwrap behind the {@code providerName()} defaults on
 * {@link LlmProvider} and {@link EmbeddingProvider}. Both SPIs derive a
 * stable, operator-facing name by walking up from a CDI client-proxy
 * subclass (whose simple name carries a framework suffix such as
 * {@code _ClientProxy}) to the developer-authored class, so a provider
 * that does not override still gets a stable name across framework
 * versions. The walk is bounded by the SPI interface passed in, so it
 * stops at the first superclass that no longer implements that SPI —
 * which is why the two callers pass their own interface. (M1-494 07#F2)
 */
final class ProviderNames {

    private ProviderNames() {}

    static String unwrapProxySimpleName(Class<?> providerClass, Class<?> spiInterface) {
        Class<?> cls = providerClass;
        while (cls.getSimpleName().contains("_") && cls.getSuperclass() != null
                && spiInterface.isAssignableFrom(cls.getSuperclass())) {
            cls = cls.getSuperclass();
        }
        return cls.getSimpleName();
    }
}
