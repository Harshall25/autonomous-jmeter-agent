package com.ai.jmeter.agent.domain;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The verdict on one JMeter run — the "observe" half of the agent's observe/act cycle.
 *
 * <p>The orchestrator makes exactly one decision from this record: terminate, or heal. When
 * healing, {@link #errorDigest()} is the evidence bundle handed to the model.
 *
 * @param status         the categorized outcome of the run
 * @param totalSamples   how many samplers JMeter recorded
 * @param failures       the failing rows, capped by the parser to keep prompts affordable
 * @param processOutput  stdout/stderr captured from the JMeter CLI
 */
public record ExecutionReport(
        ExecutionStatus status,
        int totalSamples,
        List<SampleFailure> failures,
        String processOutput) {

    private static final int DIGEST_FAILURE_LIMIT = 25;

    public ExecutionReport {
        processOutput = processOutput == null ? "" : processOutput;
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /** A clean run: every sampler passed. */
    public static ExecutionReport success(int totalSamples, String processOutput) {
        return new ExecutionReport(ExecutionStatus.SUCCESS, totalSamples, List.of(), processOutput);
    }

    /** The plan ran, but samplers were rejected. */
    public static ExecutionReport sampleFailures(
            int totalSamples, List<SampleFailure> failures, String processOutput) {
        return new ExecutionReport(
                ExecutionStatus.SAMPLE_FAILURE, totalSamples, failures, processOutput);
    }

    /** The JMeter CLI itself failed — the plan is most likely structurally invalid. */
    public static ExecutionReport processFailure(String processOutput) {
        return new ExecutionReport(ExecutionStatus.PROCESS_FAILURE, 0, List.of(), processOutput);
    }

    /** JMeter exited cleanly but exercised nothing. */
    public static ExecutionReport noSamples(String processOutput) {
        return new ExecutionReport(ExecutionStatus.NO_SAMPLES, 0, List.of(), processOutput);
    }

    public boolean successful() {
        return status.successful();
    }

    public int failedSamples() {
        return failures.size();
    }

    /**
     * Builds the error evidence handed to the self-healing prompt.
     *
     * <p>The failure list is truncated: a load test can fail thousands of times with the same
     * root cause, and sending all of it would blow the context window for no diagnostic gain.
     *
     * @return a newline-delimited digest of what went wrong
     */
    public String errorDigest() {
        StringBuilder digest = new StringBuilder("Execution status: ").append(status).append('\n')
                .append("Total samples: ").append(totalSamples).append('\n')
                .append("Failed samples: ").append(failedSamples()).append('\n');

        if (!failures.isEmpty()) {
            digest.append("Failure details:\n")
                    .append(failures.stream()
                            .limit(DIGEST_FAILURE_LIMIT)
                            .map(failure -> "  - " + failure.describe())
                            .collect(Collectors.joining("\n")))
                    .append('\n');
            if (failures.size() > DIGEST_FAILURE_LIMIT) {
                digest.append("  ... ")
                        .append(failures.size() - DIGEST_FAILURE_LIMIT)
                        .append(" further failures omitted\n");
            }
        }

        if (!processOutput.isBlank()) {
            digest.append("JMeter process output:\n").append(processOutput).append('\n');
        }
        return digest.toString();
    }
}
