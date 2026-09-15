package com.ai.jmeter.agent.domain.redaction;

import java.io.Serial;
import java.util.List;

/**
 * Raised when a capture carries material that the configured compliance policy forbids sending to
 * an external model, even after substitution.
 *
 * <p>Redaction removes the values, but under a strict policy that is not always enough: the
 * surrounding request context can still identify a subject. Operators in regulated environments
 * need the run to stop rather than proceed on a best-effort basis.
 */
public class CompliancePolicyViolationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient List<SecretCategory> violations;

    public CompliancePolicyViolationException(List<SecretCategory> violations) {
        super("Strict compliance mode refuses this capture: it contains %s. "
                .formatted(violations)
                + "Scrub the capture at source, or disable agent.compliance.strict-mode "
                + "if substituted values are acceptable for your jurisdiction.");
        this.violations = List.copyOf(violations);
    }

    public List<SecretCategory> violations() {
        return violations;
    }
}
