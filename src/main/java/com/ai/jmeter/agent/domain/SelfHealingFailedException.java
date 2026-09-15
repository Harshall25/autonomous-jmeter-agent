package com.ai.jmeter.agent.domain;

import java.io.Serial;

/**
 * Raised when the agentic loop exhausts its retry budget without reaching a clean run.
 *
 * <p>This is the agent admitting defeat: it generated, executed, analyzed and re-prompted the
 * configured number of times and the plan still fails. The final {@link ExecutionReport} is
 * carried on the exception so the operator inherits the full diagnostic trail.
 */
public class SelfHealingFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient ExecutionReport lastReport;
    private final int attempts;

    public SelfHealingFailedException(int attempts, ExecutionReport lastReport) {
        super("Self-healing failed after %d attempt(s). Last status: %s%n%s"
                .formatted(attempts, lastReport.status(), lastReport.errorDigest()));
        this.attempts = attempts;
        this.lastReport = lastReport;
    }

    public ExecutionReport lastReport() {
        return lastReport;
    }

    public int attempts() {
        return attempts;
    }
}
