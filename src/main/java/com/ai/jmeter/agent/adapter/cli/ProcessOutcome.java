package com.ai.jmeter.agent.adapter.cli;

/**
 * The result of one external process invocation.
 *
 * @param exitCode the process exit status, meaningless when {@code timedOut} is {@code true}
 * @param timedOut whether the process had to be killed for exceeding its time budget
 */
public record ProcessOutcome(int exitCode, boolean timedOut) {

    /** Exit status reported for a process the runner had to destroy. */
    public static final int TIMED_OUT_EXIT_CODE = -1;

    public static ProcessOutcome killedOnTimeout() {
        return new ProcessOutcome(TIMED_OUT_EXIT_CODE, true);
    }

    public static ProcessOutcome completed(int exitCode) {
        return new ProcessOutcome(exitCode, false);
    }

    public boolean successful() {
        return !timedOut && exitCode == 0;
    }
}
