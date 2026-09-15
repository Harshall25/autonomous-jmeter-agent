package com.ai.jmeter.agent.domain.redaction;

import java.util.Objects;

/**
 * One sensitive value lifted out of a capture and replaced by a stable placeholder.
 *
 * <p>The original value is kept in memory only so the run can bind a real credential at execution
 * time; it is never part of what reaches the model, and {@link #toString()} is overridden so it
 * cannot leak through a log statement either.
 *
 * @param category    what kind of secret this is
 * @param placeholder the JMeter variable reference substituted into the payload
 * @param secretValue the original text, held for run-time binding only
 */
public record RedactedSecret(SecretCategory category, String placeholder, String secretValue) {

    public RedactedSecret {
        Objects.requireNonNull(category, "category must not be null");
        Objects.requireNonNull(placeholder, "placeholder must not be null");
        Objects.requireNonNull(secretValue, "secretValue must not be null");
    }

    /** @return the JMeter property name an operator sets to supply the real value. */
    public String propertyName() {
        return placeholder.replace("${__P(", "").replace(")}", "");
    }

    /**
     * Deliberately omits the secret value. A record's generated {@code toString} would print it,
     * and these objects pass through logging and exception paths.
     */
    @Override
    public String toString() {
        return "RedactedSecret[category=%s, placeholder=%s, secretValue=<redacted>]"
                .formatted(category, placeholder);
    }
}
