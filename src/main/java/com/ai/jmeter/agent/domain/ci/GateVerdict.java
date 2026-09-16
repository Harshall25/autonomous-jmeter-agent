package com.ai.jmeter.agent.domain.ci;

import java.util.List;

/**
 * Whether the pipeline should go green, and what to say if not.
 *
 * @param passed  the verdict
 * @param reasons every policy breach, phrased for a pull request comment; empty when passed
 */
public record GateVerdict(boolean passed, List<String> reasons) {

    /** The exit code a failing gate returns, distinct from 1 so a crash is not read as a block. */
    public static final int BLOCKED_EXIT_CODE = 2;

    public GateVerdict {
        reasons = List.copyOf(reasons);
    }

    /** Named {@code allowed} rather than {@code passed} so it does not collide with the accessor. */
    public static GateVerdict allowed() {
        return new GateVerdict(true, List.of());
    }

    /**
     * @param reasons what the policy objected to; must not be empty
     * @return a blocking verdict
     * @throws IllegalArgumentException if no reason was given, since a gate that blocks without
     *                                  saying why is the reason teams disable performance gates
     */
    public static GateVerdict blocked(List<String> reasons) {
        if (reasons.isEmpty()) {
            throw new IllegalArgumentException("A blocking verdict must say what it objected to");
        }
        return new GateVerdict(false, reasons);
    }

    /** @return the process exit code this verdict should produce. */
    public int exitCode() {
        return passed ? 0 : BLOCKED_EXIT_CODE;
    }

    public String describe() {
        if (passed) {
            return "Performance gate passed.";
        }
        return "Performance gate blocked the build:" + System.lineSeparator()
                + String.join(System.lineSeparator(),
                        reasons.stream().map(reason -> "  - " + reason).toList());
    }
}
