package app.zcat.infochat.provider.command.asset;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jspecify.annotations.Nullable;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only registry of operator-configured assets, populated at
 * Provider startup from {@code asset_config} (DB) and
 * {@code bootstrap-assets.json} (filesystem). The Provider has
 * SELECT-only on {@code asset_config} per V14 GRANTs.
 *
 * <p>Consumers: {@link AssetCommandFamilyOracle} (probation gate),
 * {@code AssetHandler} (dispatch + validation),
 * {@code HelpCommandHandler} (/help context-awareness).</p>
 */
@ApplicationScoped
public class AssetRegistry {

    /** Per-(asset, sub_verb) runtime state from {@code asset_config}. */
    public record SubVerbEntry(
            String subVerb,
            boolean enabled,
            boolean isDefault,
            String attributionUrl,
            String defaultQuoteCurrency
    ) {}

    /** Per-asset metadata, joining DB state + bootstrap-file metadata. */
    public record AssetEntry(
            String name,
            String displayName,
            List<SubVerbEntry> subVerbs,
            List<String> supportedVsCurrencies
    ) {
        public List<String> enabledSubVerbNames() {
            List<String> names = new ArrayList<>();
            for (SubVerbEntry sv : subVerbs) {
                if (sv.enabled) {
                    names.add(sv.subVerb);
                }
            }
            return names;
        }

        public @Nullable SubVerbEntry findSubVerb(String name) {
            for (SubVerbEntry sv : subVerbs) {
                if (sv.subVerb.equals(name)) {
                    return sv;
                }
            }
            return null;
        }

        public @Nullable SubVerbEntry defaultSubVerb() {
            for (SubVerbEntry sv : subVerbs) {
                if (sv.isDefault) {
                    return sv;
                }
            }
            return null;
        }
    }

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "infochat.bootstrap.assets-file")
    Optional<String> assetsFilePath;

    private Map<String, AssetEntry> assets = Map.of();

    /** CDI-required no-arg constructor. */
    public AssetRegistry() {}

    /** Test constructor — accepts a pre-built asset map. */
    AssetRegistry(Map<String, AssetEntry> assets) {
        this.assets = Map.copyOf(assets);
    }

    void onStartup(@Observes StartupEvent event) {
        refresh();
    }

    /** Reloads the registry from DB + bootstrap file. Package-private
     *  so ITs can re-trigger after seeding test data. */
    void refresh() {
        Map<String, BootstrapMeta> meta = loadBootstrapMeta();
        Map<String, AssetEntry> loaded = loadFromDb(meta);
        this.assets = Map.copyOf(loaded);
    }

    public boolean containsEnabledAsset(String name) {
        AssetEntry entry = assets.get(name);
        if (entry == null) {
            return false;
        }
        return !entry.enabledSubVerbNames().isEmpty();
    }

    public @Nullable AssetEntry getAsset(String name) {
        return assets.get(name);
    }

    public Set<String> getEnabledAssetNames() {
        Set<String> names = new LinkedHashSet<>();
        for (Map.Entry<String, AssetEntry> e : assets.entrySet()) {
            if (!e.getValue().enabledSubVerbNames().isEmpty()) {
                names.add(e.getKey());
            }
        }
        return Collections.unmodifiableSet(names);
    }

    public List<AssetEntry> getEnabledAssets() {
        List<AssetEntry> result = new ArrayList<>();
        for (AssetEntry entry : assets.values()) {
            if (!entry.enabledSubVerbNames().isEmpty()) {
                result.add(entry);
            }
        }
        return result;
    }

    private Map<String, AssetEntry> loadFromDb(Map<String, BootstrapMeta> meta) {
        Map<String, List<SubVerbEntry>> grouped = new LinkedHashMap<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT asset, sub_verb, enabled, is_default, attribution_url, "
                             + "default_quote_currency FROM asset_config ORDER BY asset, sub_verb")) {
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String asset = rs.getString("asset");
                    SubVerbEntry entry = new SubVerbEntry(
                            rs.getString("sub_verb"),
                            rs.getBoolean("enabled"),
                            rs.getBoolean("is_default"),
                            rs.getString("attribution_url"),
                            rs.getString("default_quote_currency")
                    );
                    grouped.computeIfAbsent(asset, k -> new ArrayList<>()).add(entry);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load asset_config", e);
        }

        Map<String, AssetEntry> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<SubVerbEntry>> e : grouped.entrySet()) {
            String assetName = e.getKey();
            BootstrapMeta bm = meta.get(assetName);
            String displayName = bm != null ? bm.displayName : capitalize(assetName);
            List<String> supportedVs = bm != null ? bm.supportedVs : List.of("usd");
            result.put(assetName, new AssetEntry(assetName, displayName, e.getValue(), supportedVs));
        }
        return result;
    }

    private Map<String, BootstrapMeta> loadBootstrapMeta() {
        if (assetsFilePath.isEmpty()) {
            return Map.of();
        }
        Path path = Path.of(assetsFilePath.get());
        if (!Files.isReadable(path)) {
            return Map.of();
        }
        try (InputStream in = Files.newInputStream(path)) {
            ObjectMapper mapper = new ObjectMapper();
            BootstrapDoc doc = mapper.readValue(in, BootstrapDoc.class);
            Map<String, BootstrapMeta> result = new HashMap<>();
            if (doc.assets != null) {
                for (BootstrapAsset a : doc.assets) {
                    List<String> vs = a.supportedVs != null ? List.copyOf(a.supportedVs) : List.of("usd");
                    result.put(a.id, new BootstrapMeta(
                            a.displayName != null ? a.displayName : capitalize(a.id), vs));
                }
            }
            return result;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to parse bootstrap-assets.json at " + path, e);
        }
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) {
            return s;
        }
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private record BootstrapMeta(String displayName, List<String> supportedVs) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class BootstrapDoc {
        @JsonProperty("assets")
        @Nullable List<BootstrapAsset> assets;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class BootstrapAsset {
        // id is the asset key; consumers dereference it unguarded, so the
        // contract stays non-null. Jackson sets it reflectively, which the
        // field-init check cannot see — hence the Init suppression.
        @JsonProperty("id")
        @SuppressWarnings("NullAway.Init")
        String id;
        @JsonProperty("display_name")
        @Nullable String displayName;
        @JsonProperty("supported_vs")
        @Nullable List<String> supportedVs;
    }
}
