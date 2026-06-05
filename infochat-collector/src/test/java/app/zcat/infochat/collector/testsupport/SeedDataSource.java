package app.zcat.infochat.collector.testsupport;

import jakarta.inject.Qualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * Qualifier marking the test-only seed datasource — the seam through which
 * DB-backed @QuarkusTest fixtures obtain their JDBC connection for seeding,
 * mutating, and directly reading rows. It distinguishes the seed datasource
 * (produced by SeedDataSourceProducer) from the application default datasource
 * that production code injects unqualified, so the two can be repointed at
 * different DB roles independently: a later ticket (M1-127) gives the default
 * the least-privileged service role while leaving the seed datasource on an
 * owner role, without editing any individual test.
 */
@Qualifier
@Retention(RUNTIME)
@Target({METHOD, FIELD, PARAMETER, TYPE})
public @interface SeedDataSource {
}
