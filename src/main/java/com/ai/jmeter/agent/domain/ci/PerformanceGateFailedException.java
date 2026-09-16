package com.ai.jmeter.agent.domain.ci;

import java.io.Serial;

/**
 * Raised when a completed run breaches the pipeline's performance policy.
 *
 * <p>An exception rather than a return value because it has to reach the process exit code
 * through whatever driving adapter started the run, and a gate whose failure can be ignored by
 * forgetting to check a boolean is not a gate.
 */
public class PerformanceGateFailedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient GateVerdict verdict;

    public PerformanceGateFailedException(GateVerdict verdict) {
        super(verdict.describe());
        this.verdict = verdict;
    }

    public GateVerdict verdict() {
        return verdict;
    }
}
