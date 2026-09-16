package com.ai.jmeter.agent.adapter.governance;

import com.ai.jmeter.agent.port.ManifestSignerPort;
import com.ai.jmeter.agent.port.ProvenanceException;

/**
 * Driven adapter: the signer bound when no signing key has been configured.
 *
 * <p>It refuses rather than degrading to a no-op. A signer that quietly returned an empty string
 * would produce a provenance chain that looks complete, verifies nothing, and would be discovered
 * during the audit it was meant to satisfy — so an operator who turns on tenancy without a key
 * finds out at the first run, with a message that says what to set.
 */
public final class UnconfiguredManifestSigner implements ManifestSignerPort {

    private static final String EXPLANATION =
            "No manifest signing key is configured. Set agent.tenancy.signing-key (at least 32 "
                    + "characters, from a secret store) to record attestable run provenance.";

    @Override
    public String sign(String canonicalPayload) {
        throw new ProvenanceException(EXPLANATION);
    }

    @Override
    public boolean verify(String canonicalPayload, String signature) {
        // Fail closed: with no key, nothing can be shown to be authentic, and reporting an
        // unverifiable manifest as verified is the one outcome worse than reporting an error.
        return false;
    }
}
