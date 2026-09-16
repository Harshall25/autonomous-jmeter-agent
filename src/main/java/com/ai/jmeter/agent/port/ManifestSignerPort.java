package com.ai.jmeter.agent.port;

/**
 * Driven port: attests that a run manifest has not been edited since it was recorded.
 *
 * <p>An audit trail anyone with write access to a file can rewrite is not an audit trail. The
 * signature is what makes the provenance record evidence rather than a log line.
 */
public interface ManifestSignerPort {

    /**
     * @param canonicalPayload the deterministic rendering of the manifest
     * @return the signature to store alongside it
     * @throws ProvenanceException if no key is configured to sign with
     */
    String sign(String canonicalPayload);

    /**
     * @param canonicalPayload the payload as it stands now
     * @param signature        the signature recorded at the time
     * @return whether the payload still matches what was signed
     */
    boolean verify(String canonicalPayload, String signature);
}
