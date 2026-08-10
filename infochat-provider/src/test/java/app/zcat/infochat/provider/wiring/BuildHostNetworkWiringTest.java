package app.zcat.infochat.provider.wiring;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/** Pins M1-810: the three repo image builds declare {@code network: host} under build:
 *  (docs/plan/m1/tick-analysis/wizard-download-container-network.md); a service-level
 *  {@code network_mode} — runtime host networking — is forbidden (docs/spec/security.md §Trust boundaries). */
class BuildHostNetworkWiringTest {

    private static final List<String> COMPOSE_FILES =
            List.of("docker-compose.yml", "docker-compose.comfyui.yml", "docker-compose.gpu.yml");

    @Test
    void appImageBuildsDeclareTheHostNetwork() throws IOException {
        assertBuildDeclaresHostNetwork("docker-compose.yml", "infochat-collector");
        assertBuildDeclaresHostNetwork("docker-compose.yml", "infochat-provider");
        assertBuildDeclaresHostNetwork("docker-compose.comfyui.yml", "comfyui");
        for (String file : COMPOSE_FILES) {
            String compose = Files.readString(repoRoot().resolve(file));
            assertFalse(Pattern.compile("(?m)^\\s*network_mode\\s*:").matcher(compose).find(),
                    file + " declares network_mode: runtime host networking is forbidden as a default"
                            + " (docs/spec/security.md §Trust boundaries)");
        }
    }

    private void assertBuildDeclaresHostNetwork(String composeFile, String service) throws IOException {
        String build = buildBlock(composeFile, service);
        assertTrue(Pattern.compile("(?m)^\\s+network:\\s*host\\s*$").matcher(build).find(),
                composeFile + ": service '" + service + "' build: must declare 'network: host'"
                        + " — default-bridge build containers die on DNS on the divergent host class"
                        + " (docs/plan/m1/tick-analysis/wizard-download-container-network.md)");
    }

    private String buildBlock(String composeFile, String service) throws IOException {
        String block = serviceBlock(composeFile, service);
        Matcher build = Pattern.compile("(?m)^    build:\\s*$").matcher(block);
        assertTrue(build.find(), composeFile + ": service '" + service + "' has no build: section");
        Matcher sibling = Pattern.compile("(?m)^    [A-Za-z0-9_.-]+:\\s*$").matcher(block);
        int end = block.length();
        if (sibling.find(build.end())) {
            end = sibling.start();
        }
        return block.substring(build.end(), end);
    }

    private String serviceBlock(String composeFile, String service) throws IOException {
        String compose = Files.readString(repoRoot().resolve(composeFile));
        Matcher header = Pattern.compile("(?m)^  " + Pattern.quote(service) + ":\\s*$").matcher(compose);
        assertTrue(header.find(), composeFile + ": service '" + service + "' not found");
        Matcher next = Pattern.compile("(?m)^(  [A-Za-z0-9_.-]+:\\s*$|[A-Za-z].*$)").matcher(compose);
        int end = compose.length();
        if (next.find(header.end())) {
            end = next.start();
        }
        return compose.substring(header.start(), end);
    }

    /** Walk up from the module CWD to the repo root (the dir with docker-compose.yml). */
    private Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.exists(p.resolve("docker-compose.yml"))) {
                return p;
            }
        }
        throw new IllegalStateException("docker-compose.yml not found walking up from " + dir);
    }
}
