package app.zcat.infochat.collector.stream.nostr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link NostrEventVerifier}. Two layers:
 * <ol>
 *   <li>BIP-340 spec vectors — a representative subset of pass / fail vectors
 *       from {@code bitcoin/bips/bip-0340/test-vectors.csv}, exercised through
 *       the raw {@link NostrEventVerifier#verifySchnorr} API. Independent
 *       corroboration that the BIP-340 framing in this codebase matches the
 *       spec algorithm — not just our own Python generator.</li>
 *   <li>Nostr-event scenarios — the four named acceptance tests
 *       (valid / invalid-sig / id-mismatch / tampered-content) using real
 *       signed fixtures from {@link NostrSignedEventFixtures}.</li>
 * </ol>
 */
class NostrEventVerifierTest {

    private final NostrEventVerifier verifier = new NostrEventVerifier();

    // --- BIP-340 spec test vectors (pass + fail subset) ---

    /**
     * Vectors copied verbatim from
     * https://github.com/bitcoin/bips/blob/master/bip-0340/test-vectors.csv
     * — indices 0, 1, 2, 3 (TRUE) and 5, 6, 7, 8 (FALSE). Indices match
     * the upstream CSV so a future reviewer can re-verify by line lookup.
     */
    static Stream<Arguments> bip340Vectors() {
        return Stream.of(
                // index, pubkey32, msg32, sig64, expected
                Arguments.of(0,
                        "F9308A019258C31049344F85F89D5229B531C845836F99B08601F113BCE036F9",
                        "0000000000000000000000000000000000000000000000000000000000000000",
                        "E907831F80848D1069A5371B402410364BDF1C5F8307B0084C55F1CE2DCA8215"
                                + "25F66A4A85EA8B71E482A74F382D2CE5EBEEE8FDB2172F477DF4900D310536C0",
                        true),
                Arguments.of(1,
                        "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
                        "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                        "6896BD60EEAE296DB48A229FF71DFE071BDE413E6D43F917DC8DCF8C78DE3341"
                                + "8906D11AC976ABCCB20B091292BFF4EA897EFCB639EA871CFA95F6DE339E4B0A",
                        true),
                Arguments.of(2,
                        "DD308AFEC5777E13121FA72B9CC1B7CC0139715309B086C960E18FD969774EB8",
                        "7E2D58D8B3BCDF1ABADEC7829054F90DDA9805AAB56C77333024B9D0A508B75C",
                        "5831AAEED7B44BB74E5EAB94BA9D4294C49BCF2A60728D8B4C200F50DD313C1B"
                                + "AB745879A5AD954A72C45A91C3A51D3C7ADEA98D82F8481E0E1E03674A6F3FB7",
                        true),
                Arguments.of(3,
                        "25D1DFF95105F5253C4022F628A996AD3A0D95FBF21D468A1B33F8C160D8F517",
                        "FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF",
                        "7EB0509757E246F19449885651611CB965ECC1A187DD51B64FDA1EDC9637D5EC"
                                + "97582B9CB13DB3933705B32BA982AF5AF25FD78881EBB32771FC5922EFC66EA3",
                        true),
                Arguments.of(5,
                        // pubkey not on the curve
                        "EEFDEA4CDB677750A420FEE807EACF21EB9898AE79B9768766E4FAA04A2D4A34",
                        "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                        "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177760"
                                + "69E89B4C5564D00349106B8497785DD7D1D713A8AE82B32FA79D5F7FC407D39B",
                        false),
                Arguments.of(6,
                        // has_even_y(R) is false
                        "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
                        "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                        "FFF97BD5755EEEA420453A14355235D382F6472F8568A18B2F057A146029755"
                                + "63CC27944640AC607CD107AE10923D9EF7A73C643E166BE5EBEAFA34B1AC553E2",
                        false),
                Arguments.of(7,
                        // negated message
                        "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
                        "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                        "1FA62E331EDBC21C394792D2AB1100A7B432B013DF3F6FF4F99FCB33E0E1515F"
                                + "28890B3EDB6E7189B630448B515CE4F8622A954CFE545735AAEA5134FCCDB2BD",
                        false),
                Arguments.of(8,
                        // negated s value
                        "DFF1D77F2A671C5F36183726DB2341BE58FEAE1DA2DECED843240F7B502BA659",
                        "243F6A8885A308D313198A2E03707344A4093822299F31D0082EFA98EC4E6C89",
                        "6CFF5C3BA86C69EA4B7376F31A9BCB4F74C1976089B2D9963DA2E5543E177769"
                                + "61764B3AA9B2FFCB6EF947B6887A226E8D7C93E00C5ED0C1834FF0D0C2E6DA6",
                        false)
        );
    }

    @ParameterizedTest(name = "BIP-340 vector {0}: expected={4}")
    @MethodSource("bip340Vectors")
    void bip340SpecVector(int index, String pubkeyHex, String msgHex, String sigHex, boolean expected) {
        byte[] pubkey = hexToBytes(pubkeyHex);
        byte[] msg = hexToBytes(msgHex);
        byte[] sig = hexToBytes(sigHex);
        boolean actual = verifier.verifySchnorr(pubkey, msg, sig);
        if (expected) {
            assertTrue(actual, "BIP-340 vector " + index + " should verify");
        } else {
            assertFalse(actual, "BIP-340 vector " + index + " should be rejected");
        }
    }

    // --- Named acceptance tests on the high-level verify(NostrEvent) API ---

    @Test
    void validSignature_passes() {
        assertTrue(verifier.verify(NostrSignedEventFixtures.VALID_KIND_1_EVENT),
                "a real BIP-340-signed Nostr event verifies");
    }

    @Test
    void invalidSignature_rejected() {
        NostrEvent valid = NostrSignedEventFixtures.VALID_KIND_1_EVENT;
        // Flip one nibble in the middle of the sig — still well-formed hex
        // and the right length, but no longer a valid BIP-340 signature.
        String tamperedSig = flipNibbleAt(valid.sig(), 32);
        NostrEvent tampered = new NostrEvent(valid.id(), valid.pubkey(), valid.createdAt(),
                valid.kind(), valid.tags(), valid.content(), tamperedSig);
        assertFalse(verifier.verify(tampered), "a sig-tampered event is rejected");
    }

    @Test
    void idMismatch_rejected() {
        NostrEvent valid = NostrSignedEventFixtures.VALID_KIND_1_EVENT;
        // Substitute a different 32-byte hex hash for the id. The canonical
        // serialization still produces the original id, which no longer
        // matches the claimed id → verify rejects before touching the sig.
        String fakeId = "0000000000000000000000000000000000000000000000000000000000000001";
        NostrEvent tampered = new NostrEvent(fakeId, valid.pubkey(), valid.createdAt(),
                valid.kind(), valid.tags(), valid.content(), valid.sig());
        assertFalse(verifier.verify(tampered), "an id-substituted event is rejected");
    }

    @Test
    void tamperedContent_rejected() {
        NostrEvent valid = NostrSignedEventFixtures.VALID_KIND_1_EVENT;
        // Modify content; the relay-supplied id no longer matches the
        // canonical serialization of the modified event. (Equivalent attack
        // surface to idMismatch_rejected, but from the other side.)
        NostrEvent tampered = new NostrEvent(valid.id(), valid.pubkey(), valid.createdAt(),
                valid.kind(), valid.tags(), valid.content() + " — appended by attacker", valid.sig());
        assertFalse(verifier.verify(tampered), "a content-tampered event is rejected");
    }

    // --- Cross-check: kind-6 and kind-7 fixtures both verify too ---
    // The kind filter is enforced by NostrStreamSource, not the verifier;
    // verify() runs first and must accept all signature-valid events
    // regardless of kind. NostrStreamSourceVerificationIT covers the
    // post-verify kind drop.

    @Test
    void verifierIsKindAgnostic() {
        assertTrue(verifier.verify(NostrSignedEventFixtures.VALID_KIND_6_EVENT),
                "kind-6 fixture verifies (kind filtering is the caller's job)");
        assertTrue(verifier.verify(NostrSignedEventFixtures.VALID_KIND_7_EVENT),
                "kind-7 fixture verifies (kind filtering is the caller's job)");
    }

    @Test
    void malformedFieldsRejected() {
        NostrEvent valid = NostrSignedEventFixtures.VALID_KIND_1_EVENT;
        // Truncated id hex.
        assertFalse(verifier.verify(new NostrEvent("ab", valid.pubkey(), valid.createdAt(),
                        valid.kind(), valid.tags(), valid.content(), valid.sig())),
                "short id hex rejected");
        // Non-hex pubkey.
        assertFalse(verifier.verify(new NostrEvent(valid.id(),
                        "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz",
                        valid.createdAt(), valid.kind(), valid.tags(), valid.content(), valid.sig())),
                "non-hex pubkey rejected");
        // Null content.
        assertFalse(verifier.verify(new NostrEvent(valid.id(), valid.pubkey(), valid.createdAt(),
                        valid.kind(), valid.tags(), null, valid.sig())),
                "null content rejected");
        // Null tags.
        assertFalse(verifier.verify(new NostrEvent(valid.id(), valid.pubkey(), valid.createdAt(),
                        valid.kind(), null, valid.content(), valid.sig())),
                "null tags rejected");
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length() / 2;
        byte[] out = new byte[len];
        for (int i = 0; i < len; i++) {
            out[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return out;
    }

    private static String flipNibbleAt(String hex, int pos) {
        char[] chars = hex.toCharArray();
        int nibble = Character.digit(chars[pos], 16);
        chars[pos] = Character.forDigit(nibble ^ 0x1, 16);
        return new String(chars);
    }
}
