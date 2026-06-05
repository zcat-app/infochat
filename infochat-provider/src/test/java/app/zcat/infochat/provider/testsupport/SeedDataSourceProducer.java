package app.zcat.infochat.provider.testsupport;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

import javax.sql.DataSource;

/**
 * The single, repointable source of the test seed datasource (see
 * {@code @SeedDataSource}). It returns the owner-role {@code owner} datasource
 * (declared {@code %test}-only — the production Provider carries no owner
 * credentials): production code injects the unqualified default (bound to the
 * least-privileged service role), while every fixture seed/mutation/read flows
 * through this owner-role seam — fixtures may freely write collector-owned
 * tables that the service role cannot. Keeping the owner hop confined to this
 * method is the whole purpose of the seam: no individual test knows which role
 * seeds its data.
 */
@ApplicationScoped
class SeedDataSourceProducer {

    @Produces
    @SeedDataSource
    DataSource seedDataSource(@io.quarkus.agroal.DataSource("owner") DataSource ownerDataSource) {
        return ownerDataSource;
    }
}
