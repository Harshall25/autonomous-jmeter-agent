package com.ai.jmeter.agent.adapter.governance;

import com.ai.jmeter.agent.port.ManifestSignerPort;
import com.ai.jmeter.agent.port.ProvenanceException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Driven adapter: signs run manifests with HMAC-SHA256.
 *
 * <p>A symmetric MAC rather than a signature, because the verifier here is the same organization
 * that produced the record: the property being defended is that a manifest cannot be edited after
 * the fact by someone with write access to the store, not that a third party can verify it
 * without a shared secret. An organization that needs the latter swaps this adapter for one
 * backed by their KMS, which is exactly what the port is for.
 */
public final class HmacManifestSigner implements ManifestSignerPort {

    private static final String ALGORITHM = "HmacSHA256";

    /** Short enough for a key that was pasted from an example rather than generated. */
    private static final int MINIMUM_KEY_LENGTH = 32;

    private final SecretKeySpec key;
    private final String algorithm;

    /**
     * @param signingKey the shared secret; must be at least 32 characters
     * @throws IllegalArgumentException if the key is missing or too short to be worth having,
     *                                  because a provenance chain signed with a guessable key
     *                                  reads exactly like one that is genuinely protected
     */
    public HmacManifestSigner(String signingKey) {
        this(signingKey, ALGORITHM);
    }

    /** Seam: lets a test drive the "this JVM has no such algorithm" path. */
    HmacManifestSigner(String signingKey, String algorithm) {
        if (signingKey == null || signingKey.strip().length() < MINIMUM_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    "A manifest signing key of at least %d characters is required; run provenance "
                            .formatted(MINIMUM_KEY_LENGTH)
                            + "signed with a guessable key is indistinguishable from unprotected.");
        }
        this.algorithm = algorithm;
        this.key = new SecretKeySpec(
                signingKey.strip().getBytes(StandardCharsets.UTF_8), algorithm);
    }

    @Override
    public String sign(String canonicalPayload) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(key);
            return HexFormat.of().formatHex(
                    mac.doFinal(canonicalPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException | InvalidKeyException e) {
            throw new ProvenanceException("Unable to sign the run manifest", e);
        }
    }

    @Override
    public boolean verify(String canonicalPayload, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        // Constant-time: a byte-by-byte comparison that returns early leaks how much of a
        // forged signature was correct, which is enough to construct one a byte at a time.
        return MessageDigest.isEqual(
                sign(canonicalPayload).getBytes(StandardCharsets.UTF_8),
                signature.getBytes(StandardCharsets.UTF_8));
    }
}
