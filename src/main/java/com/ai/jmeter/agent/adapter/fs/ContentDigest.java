package com.ai.jmeter.agent.adapter.fs;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Digests artifact content, so a provenance record can be matched back to the file it attests to.
 *
 * <p>Prefixed with the algorithm, as {@code sha256:…}, because a bare hex string in an audit
 * record is unverifiable the day the algorithm changes and nobody recorded which one produced it.
 */
final class ContentDigest {

    private static final String DEFAULT_ALGORITHM = "SHA-256";

    private final String algorithm;

    ContentDigest() {
        this(DEFAULT_ALGORITHM);
    }

    /** Seam: lets a test drive the "this JVM has no such algorithm" path. */
    ContentDigest(String algorithm) {
        this.algorithm = algorithm;
    }

    /**
     * @param content the bytes as they were written
     * @return the prefixed hex digest
     * @throws IllegalStateException if the algorithm is unavailable, which for SHA-256 means the
     *                               JVM is not a conformant one
     */
    String of(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            return algorithm.replace("-", "").toLowerCase(java.util.Locale.ROOT) + ":"
                    + HexFormat.of().formatHex(
                            digest.digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(
                    "This JVM cannot provide " + algorithm + ", so artifacts cannot be attested",
                    e);
        }
    }
}
