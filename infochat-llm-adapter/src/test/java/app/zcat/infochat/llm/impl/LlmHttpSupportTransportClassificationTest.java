package app.zcat.infochat.llm.impl;

import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct unit contract of {@link LlmHttpSupport#isTransportUnreachable}
 * (M1-606): the single classification site deciding which HTTP-call
 * failures count as "the endpoint itself is unreachable" (and so may
 * advance a circuit breaker's consecutive-failure count) versus
 * application-level failures from an endpoint that answered. The
 * decorator-level attribution tests in
 * {@code LlmCircuitBreakerRegistryTest} consume already-classified
 * exceptions; this test pins the classification itself, including the
 * cause-chain walk the JDK {@link java.net.http.HttpClient}'s wrapping
 * behaviour requires.
 */
class LlmHttpSupportTransportClassificationTest {

    // --- direct unreachable subtypes ---

    @Test
    void connectionRefusedClassifiesUnreachable() {
        assertTrue(LlmHttpSupport.isTransportUnreachable(
                new ConnectException("Connection refused")));
    }

    @Test
    void dnsFailureClassifiesUnreachable() {
        assertTrue(LlmHttpSupport.isTransportUnreachable(
                new UnknownHostException("llm.invalid")));
    }

    @Test
    void noRouteToHostClassifiesUnreachable() {
        assertTrue(LlmHttpSupport.isTransportUnreachable(
                new NoRouteToHostException("No route to host")));
    }

    @Test
    void requestTimeoutClassifiesUnreachable() {
        assertTrue(LlmHttpSupport.isTransportUnreachable(
                new HttpTimeoutException("request timed out")));
    }

    @Test
    void connectTimeoutClassifiesUnreachable() {
        // HttpConnectTimeoutException extends HttpTimeoutException; pinned
        // separately because it is the shape the connect phase actually
        // throws.
        assertTrue(LlmHttpSupport.isTransportUnreachable(
                new HttpConnectTimeoutException("HTTP connect timed out")));
    }

    // --- cause-chain-wrapped forms (the JDK HttpClient wraps the
    // discriminating exception at varying depths) ---

    @Test
    void wrappedConnectExceptionClassifiesUnreachable() {
        assertTrue(LlmHttpSupport.isTransportUnreachable(
                new IOException("HTTP call failed",
                        new ConnectException("Connection refused"))));
    }

    @Test
    void deeplyWrappedDnsFailureClassifiesUnreachable() {
        assertTrue(LlmHttpSupport.isTransportUnreachable(
                new IOException("outer",
                        new IOException("middle",
                                new UnknownHostException("llm.invalid")))));
    }

    // --- application-class failures: the endpoint answered (or the
    // failure says nothing about reachability) — must NOT classify ---

    @Test
    void bareIoExceptionDoesNotClassifyUnreachable() {
        // The bounded-body-cap shape: the endpoint responded (too much).
        assertFalse(LlmHttpSupport.isTransportUnreachable(
                new IOException("LLM response body exceeded the 8388608-byte cap")));
    }

    @Test
    void otherIoExceptionSubtypesDoNotClassifyUnreachable() {
        // A TLS handshake failure reaches a listening endpoint — not the
        // unreachable class; the safe mis-classification direction is
        // "not unreachable" (breaker trips less eagerly).
        assertFalse(LlmHttpSupport.isTransportUnreachable(
                new SSLException("handshake failed")));
    }

    @Test
    void wrappedNonTransportCauseDoesNotClassifyUnreachable() {
        // The cause-chain walk must not turn an arbitrary wrapped
        // IOException into a transport verdict: no unreachable subtype
        // anywhere in the chain → false.
        assertFalse(LlmHttpSupport.isTransportUnreachable(
                new IOException("parse failure",
                        new IOException("unexpected end of JSON input"))));
    }
}
