package app.zcat.infochat.collector.stream.nostr;

import java.util.List;

/**
 * Real BIP-340-signed Nostr event fixtures shared across NostrEventVerifierTest,
 * NostrStreamSourceVerificationIT, NostrStreamSourceTest, and NostrStreamSourceIT.
 *
 * <p>Every event here was produced by the BIP-340 reference implementation from
 * the BIP spec (the Python reference.py shipped with bip-0340) using a fixed
 * deterministic test seckey. The events therefore round-trip through any
 * spec-conformant BIP-340 verifier (including NostrEventVerifier) without
 * touching production code or production keys.</p>
 *
 * <p><b>Regeneration.</b> The generator script lives at
 * {@code .scratch/gen-nostr-fixtures.py} (gitignored). It uses:</p>
 * <ul>
 *   <li>seckey = 32 bytes of {@code 0x01}</li>
 *   <li>aux_rand = 32 bytes of {@code 0x00} (deterministic nonce)</li>
 *   <li>created_at = {@code 1700000000} (a fixed unix-second timestamp)</li>
 *   <li>tags = {@code []} (empty)</li>
 *   <li>content = the per-kind constants below</li>
 * </ul>
 * <p>Running the script re-prints the constants below verbatim. If a future
 * change perturbs any field, regenerate, paste the new values, and update the
 * BIP-340 vector test in {@link NostrEventVerifierTest} only if vectors there
 * also change (they do not depend on this seckey).</p>
 *
 * <p>Package-private — these fixtures are not part of any public surface and
 * must not leak outside the test sources of the nostr package.</p>
 */
final class NostrSignedEventFixtures {

    /** Deterministic test pubkey (x-only, 32-byte hex) derived from seckey 0x01..01. */
    static final String TEST_PUBKEY = "1b84c5567b126440995d3ed5aaba0565d71e1834604819ff9c17f5e9d5dd078f";

    /** Deterministic fixed-second timestamp baked into every fixture id. */
    static final long FIXED_CREATED_AT = 1700000000L;

    // --- VALID_KIND_1_EVENT ---
    static final String KIND_1_CONTENT = "Test note for M1-097 verifier";
    static final String KIND_1_ID = "48bcd1ccd23986a1173f24dc36e4b03c5b8d5d5e4803584a10b7c6a3a6fa91cf";
    static final String KIND_1_SIG =
            "fc4034cb684c55cc6c86e5c95f22ca204e98ccc90f6f74626126b90599df980a"
                    + "9bd3154c862193c264195588d930b893f4758b120e41a24ceb6d99b7872f0571";

    static final NostrEvent VALID_KIND_1_EVENT = new NostrEvent(
            KIND_1_ID, TEST_PUBKEY, FIXED_CREATED_AT, 1, List.of(), KIND_1_CONTENT, KIND_1_SIG);

    // --- VALID_KIND_6_EVENT ---
    static final String KIND_6_CONTENT = "Test repost for M1-097 verifier";
    static final String KIND_6_ID = "eb38d30dee6c4b352ed5dd93f345bea80cbdb7158d7061d54502150221bc1a34";
    static final String KIND_6_SIG =
            "9195ff180da9d74d592144ab6feddbb521fde7e7f2024dc0c7aa77545913d97e"
                    + "c72d9b53ed12b2ea0f54de313e94409307d200f992f0da63d63a62ea23983c17";

    static final NostrEvent VALID_KIND_6_EVENT = new NostrEvent(
            KIND_6_ID, TEST_PUBKEY, FIXED_CREATED_AT, 6, List.of(), KIND_6_CONTENT, KIND_6_SIG);

    // --- VALID_KIND_7_EVENT ---
    // Real BIP-340 signature — the kind-filter ITs need a signature-valid
    // event of a disallowed kind, so the test isolates the kind gate from
    // the signature gate.
    static final String KIND_7_CONTENT = "+";
    static final String KIND_7_ID = "d48c6e85bd1b801344287d03324d5fcd7ff7b4151741f0250f63d2140e3afe73";
    static final String KIND_7_SIG =
            "974cd7e1308d271dd42cb5f5ce5af7fe6de72059e4c14a115447b3ebeaaf80c8"
                    + "5d6bb249b53873b5c2034efc8681010e85141784386bc8375209bbb2d2adcbb4";

    static final NostrEvent VALID_KIND_7_EVENT = new NostrEvent(
            KIND_7_ID, TEST_PUBKEY, FIXED_CREATED_AT, 7, List.of(), KIND_7_CONTENT, KIND_7_SIG);

    // Three additional kind-1 events used by stop-drain tests that need
    // multiple distinct signature-valid events queued simultaneously.
    static final NostrEvent VALID_KIND_1_DRAIN_A_EVENT = new NostrEvent(
            "10ccff9bfde16da6282877bdeb26a1d62ea4b5ba80ea405b55bfe3f0ddbf4ea8",
            TEST_PUBKEY, FIXED_CREATED_AT, 1, List.of(), "Drain test event 1",
            "405ff2f2bd57d53af948806f7ca227f4d299934da5a80bdf40f9ea5a5119dc5a"
                    + "2b0ebec69fbb6538bdcfc3408fc7eb63f28be4b8d40989e0174013086ed1fe4e");
    static final NostrEvent VALID_KIND_1_DRAIN_B_EVENT = new NostrEvent(
            "51fca52aa5b08d357f89eb93335720dc687e3308f694ed0c6f74d53495435852",
            TEST_PUBKEY, FIXED_CREATED_AT, 1, List.of(), "Drain test event 2",
            "94759700a7ddc96df90ecfb5e384eea0017f8cc2a179367778b817a6f4c2328f"
                    + "3062b6f561376f7412ac0a54a3e2fed52d79cffb7bb73da6a3b60472d10f3004");
    static final NostrEvent VALID_KIND_1_DRAIN_C_EVENT = new NostrEvent(
            "0bfb2c82ff1629ec62d8c88f0923beb44bcfd54a84ee685a21b22c060276773d",
            TEST_PUBKEY, FIXED_CREATED_AT, 1, List.of(), "Drain test event 3",
            "00e534159a99a6fa32e38cc58653262776335d68790934ba958e3b826ba06cfb"
                    + "a6fca6fa277f9be9a6c51ee1b281146e4fbd2d196d98bfc019c4eba1ef3ea704");

    private NostrSignedEventFixtures() {
    }
}
