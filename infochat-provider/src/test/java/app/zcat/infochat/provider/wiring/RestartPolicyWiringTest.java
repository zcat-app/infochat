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

/** Pins M1-830: every long-running service survives dockerd restarts — a daemon
 *  bounce (OS reboot, rootless logout kill) must not strand the database or the
 *  apps while the LLM backends come back alone. {@code always} is forbidden: it
 *  would resurrect containers after a deliberate operator stop. */
class RestartPolicyWiringTest {

    @Test
    void everyLongRunningServiceRestartsUnlessStopped() throws IOException {
        assertRestartsUnlessStopped("docker-compose.yml", "postgres");
        assertRestartsUnlessStopped("docker-compose.yml", "infochat-collector");
        assertRestartsUnlessStopped("docker-compose.yml", "infochat-provider");
        assertRestartsUnlessStopped("docker-compose.yml", "ollama");
        assertRestartsUnlessStopped("docker-compose.yml", "llamacpp");
        assertRestartsUnlessStopped("docker-compose.yml", "llamacpp-embeddings");
        assertRestartsUnlessStopped("docker-compose.comfyui.yml", "comfyui");
        for (String file : List.of("docker-compose.yml", "docker-compose.comfyui.yml", "docker-compose.gpu.yml")) {
            String compose = Files.readString(repoRoot().resolve(file));
            assertFalse(Pattern.compile("(?m)^\\s*restart:\\s*always\\s*$").matcher(compose).find(),
                    file + " declares restart: always — containers would resurrect after a deliberate"
                            + " operator stop (docker compose stop / stack.sh stop)");
        }
    }

    private void assertRestartsUnlessStopped(String composeFile, String service) throws IOException {
        String block = serviceBlock(composeFile, service);
        assertTrue(Pattern.compile("(?m)^    restart:\\s*unless-stopped\\s*$").matcher(block).find(),
                composeFile + ": service '" + service + "' must declare 'restart: unless-stopped'"
                        + " so a dockerd bounce restarts it instead of stranding the stack half-alive");
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
