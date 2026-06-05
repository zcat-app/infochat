package app.zcat.infochat.provider.testsupport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import javax.sql.DataSource;

/**
 * The single, repointable source of the test seed datasource (see
 * {@code @SeedDataSource}). Today it returns the application default datasource,
 * so DB-backed test behavior is identical to injecting the default directly.
 *
 * <p>This producer is the one point a later ticket (M1-127) edits to repoint
 * fixture seeding at an owner-role datasource: production code keeps injecting
 * the unqualified default (then bound to the least-privileged service role),
 * while every fixture seed/mutation/read flows through this owner-role seam.
 * Keeping that swap confined to this method is the whole purpose of the seam —
 * no individual test changes when the role split lands.</p>
 */
@ApplicationScoped
class SeedDataSourceProducer {

    @Produces
    @SeedDataSource
    DataSource seedDataSource(DataSource defaultDataSource) {
        return defaultDataSource;
    }
}
