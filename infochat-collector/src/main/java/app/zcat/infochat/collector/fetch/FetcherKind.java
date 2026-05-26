package app.zcat.infochat.collector.fetch;

import jakarta.enterprise.util.AnnotationLiteral;
import jakarta.inject.Qualifier;
import org.jspecify.annotations.NonNull;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * CDI qualifier that binds a {@link app.zcat.infochat.core.ingest.Fetcher}
 * bean to a {@code source.kind} value. {@link FetchScheduler} discovers
 * all qualified Fetcher beans at startup and builds a kind&rarr;Fetcher
 * dispatch map; sources whose kind matches a registered Fetcher are
 * dispatched to it, others are skipped with a WARN log.
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, METHOD, FIELD, PARAMETER})
public @interface FetcherKind {

    /** The {@code source.kind} value this Fetcher handles (e.g. {@code "rss"}). */
    String value();

    /** Programmatic qualifier literal for CDI lookups and QuarkusMock. */
    final class Literal extends AnnotationLiteral<FetcherKind> implements FetcherKind {
        private final String value;

        public Literal(@NonNull String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }
    }
}
