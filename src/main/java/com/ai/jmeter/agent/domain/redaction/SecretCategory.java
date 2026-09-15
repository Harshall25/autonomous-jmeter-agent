package com.ai.jmeter.agent.domain.redaction;

/**
 * The classes of sensitive material that must never leave the process boundary.
 *
 * <p>The category drives two decisions: how the value is substituted in the payload handed to the
 * model, and whether the run may proceed at all under a strict compliance policy.
 */
public enum SecretCategory {

    /** {@code Authorization: Bearer ...} and raw JWTs. Correlated at run time, never sent. */
    CREDENTIAL("credential", true),

    /** Session cookies and CSRF tokens — identity material for a real user. */
    SESSION("session", true),

    /** Static API keys and connection strings embedded in headers or query strings. */
    API_KEY("apiKey", true),

    /** Primary account numbers. Presence alone puts a capture in PCI scope. */
    PAYMENT_CARD("pan", true),

    /** National identifiers such as US SSN. */
    NATIONAL_ID("nationalId", true),

    /** Email addresses — personal data, but safe to shape-preserve for realistic test data. */
    EMAIL("email", false),

    /** PEM-encoded private keys. Catastrophic to leak, never shape-preserved. */
    PRIVATE_KEY("privateKey", true);

    private final String placeholderPrefix;
    private final boolean blocksStrictCompliance;

    SecretCategory(String placeholderPrefix, boolean blocksStrictCompliance) {
        this.placeholderPrefix = placeholderPrefix;
        this.blocksStrictCompliance = blocksStrictCompliance;
    }

    public String placeholderPrefix() {
        return placeholderPrefix;
    }

    /**
     * @return {@code true} when a capture carrying this category must not be sent to an external
     * model even in redacted form under the strictest policy, because the surrounding context can
     * still identify the subject
     */
    public boolean blocksStrictCompliance() {
        return blocksStrictCompliance;
    }
}
