package io.infochat.provider.config;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Plain-JUnit verification of the {@link InfochatProfile} enum and its
 * {@link InfochatProfile#resolveOrThrow(List)} static helper. The CDI
 * {@link InfochatProfile.Validator} bean is exercised implicitly by every
 * {@code @QuarkusTest} in this module (the bean fires on startup; an
 * unrecognised profile chain would prevent the Quarkus container from
 * starting at all).
 */
class InfochatProfileTest {

    @Test
    void enumHasExactlyFourValues() {
        assertEquals(4, InfochatProfile.values().length);
        assertNotNull(InfochatProfile.LAPTOP);
        assertNotNull(InfochatProfile.VPS);
        assertNotNull(InfochatProfile.PI);
        assertNotNull(InfochatProfile.REMOTE_LLM);
    }

    @Test
    void configNamesMatchSpecConvention() {
        assertEquals("laptop", InfochatProfile.LAPTOP.configName());
        assertEquals("vps", InfochatProfile.VPS.configName());
        assertEquals("pi", InfochatProfile.PI.configName());
        assertEquals("remote-llm", InfochatProfile.REMOTE_LLM.configName());
    }

    @Test
    void fromConfigNameMatchesEachKnownProfile() {
        assertEquals(Optional.of(InfochatProfile.LAPTOP), InfochatProfile.fromConfigName("laptop"));
        assertEquals(Optional.of(InfochatProfile.VPS), InfochatProfile.fromConfigName("vps"));
        assertEquals(Optional.of(InfochatProfile.PI), InfochatProfile.fromConfigName("pi"));
        assertEquals(Optional.of(InfochatProfile.REMOTE_LLM), InfochatProfile.fromConfigName("remote-llm"));
    }

    @Test
    void fromConfigNameIsCaseInsensitive() {
        assertEquals(Optional.of(InfochatProfile.LAPTOP), InfochatProfile.fromConfigName("LAPTOP"));
        assertEquals(Optional.of(InfochatProfile.REMOTE_LLM), InfochatProfile.fromConfigName("Remote-LLM"));
    }

    @Test
    void fromConfigNameReturnsEmptyForUnknown() {
        assertTrue(InfochatProfile.fromConfigName("unknown").isEmpty());
        assertTrue(InfochatProfile.fromConfigName("test").isEmpty());
        assertTrue(InfochatProfile.fromConfigName("dev").isEmpty());
        assertTrue(InfochatProfile.fromConfigName("prod").isEmpty());
        assertTrue(InfochatProfile.fromConfigName("").isEmpty());
        assertTrue(InfochatProfile.fromConfigName(null).isEmpty());
    }

    @Test
    void resolveOrThrowFailsFastOnUnknownProfileChain() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> InfochatProfile.resolveOrThrow(List.of("test", "unrecognised")));
        // The error message must name the offending chain so the operator can see what was set.
        assertTrue(ex.getMessage().contains("test"),
            "Error message must include the offending profile chain: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("unrecognised"),
            "Error message must include the offending profile chain: " + ex.getMessage());
        // The error message must list the allowed values so the operator knows what to set.
        assertTrue(ex.getMessage().contains("laptop"),
            "Error message must list allowed profiles: " + ex.getMessage());
    }

    @Test
    void resolveOrThrowFailsOnEmptyChain() {
        assertThrows(IllegalStateException.class,
            () -> InfochatProfile.resolveOrThrow(List.of()));
        assertThrows(IllegalStateException.class,
            () -> InfochatProfile.resolveOrThrow(null));
    }

    @Test
    void resolveOrThrowPicksFirstMatchInChain() {
        // Active=test, parent=laptop — laptop is the only infochat profile in the chain, so laptop wins.
        assertEquals(InfochatProfile.LAPTOP,
            InfochatProfile.resolveOrThrow(List.of("test", "laptop")));
        // Active=vps takes precedence even if laptop appears later (priority order, not preference).
        assertEquals(InfochatProfile.VPS,
            InfochatProfile.resolveOrThrow(List.of("vps", "laptop")));
        assertEquals(InfochatProfile.REMOTE_LLM,
            InfochatProfile.resolveOrThrow(List.of("remote-llm")));
    }
}
