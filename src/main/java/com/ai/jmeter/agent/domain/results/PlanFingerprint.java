package com.ai.jmeter.agent.domain.results;

import com.ai.jmeter.agent.domain.ExecutionMode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Identifies "the same test" across runs, so results can be compared over time.
 *
 * <p>Derived from the mode and the set of sampler names, not from the plan's XML. That is the whole
 * design: the agent rewrites the XML on every healing turn, so an XML hash would change constantly
 * and every run would look like a brand-new test with no history. What makes two runs comparable is
 * that they exercise the same endpoints, and sampler names are the stable expression of that.
 *
 * <p>Adding or removing an endpoint deliberately breaks the fingerprint. That is correct — the
 * workload genuinely changed, and comparing latency across a changed workload would produce a
 * regression alert nobody could act on.
 */
public final class PlanFingerprint {

    private PlanFingerprint() {
    }

    /**
     * @param mode         the ingestion mode the plan was built for
     * @param samplerNames every sampler in the plan, in any order
     * @return a stable hex fingerprint
     */
    public static String of(ExecutionMode mode, List<String> samplerNames) {
        String canonical = mode.name() + "|"
                + samplerNames.stream().sorted().distinct().toList();
        // A name-based UUID rather than a MessageDigest: this is an identity, not a security
        // boundary, and nameUUIDFromBytes gives the same determinism without the checked
        // exception every caller would then have to pretend might happen.
        return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "")
                .substring(0, 16);
    }
}
