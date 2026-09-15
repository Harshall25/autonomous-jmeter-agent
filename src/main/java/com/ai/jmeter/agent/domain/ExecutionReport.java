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
    private static final int SIGNATURE_SAMPLE_LIMIT = 8;

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

    /**
     * The plan was rejected before execution. Carries the validator's findings in place of process
     * output, so the healing loop consumes it through exactly the same path as a real failure.
     */
    public static ExecutionReport validationFailure(String findings) {
        return new ExecutionReport(ExecutionStatus.VALIDATION_FAILURE, 0, List.of(), findings);
    }

    public boolean successful() {
        return status.successful();
    }

    public int failedSamples() {
        return failures.size();
    }

    /**
     * A stable fingerprint of what went wrong, used to look up past repairs.
     *
     * <p>Deliberately lossy. It keeps the status and the distinct sampler/response-code pairs and
     * discards timings, counts and message text, so the same defect on the same endpoint produces
     * the same signature across runs and across services with similar shapes. A signature that
     * included volatile detail would never match twice and the memory would never pay off.
     *
     * @return the failure fingerprint
     */
    public String failureSignature() {
        if (failures.isEmpty()) {
            return status.name();
        }
        String samplers = failures.stream()
                .map(failure -> failure.responseCode() + ":" + failure.label())
                .distinct()
                .sorted()
                .limit(SIGNATURE_SAMPLE_LIMIT)
                .collect(Collectors.joining(","));
        return status.name() + "|" + samplers;
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
