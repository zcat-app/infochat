package app.zcat.infochat.provider.command.asset;

/** Test-scope bridge: AssetRegistry.refresh() is package-private so ITs in command.asset re-trigger the load after JDBC seeding; chat-tool ITs seed asset_config the same way and need the same re-trigger without a second registry. */
public final class AssetRegistryTestRefresh {

    private AssetRegistryTestRefresh() {
    }

    public static void refresh(AssetRegistry registry) {
        registry.refresh();
    }
}
