package com.ai.jmeter.agent.domain;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * The instruction that kicks off one agentic workflow.
 *
 * @param mode          which ingestion strategy to apply
 * @param sourceFile    the capture or specification to learn the test plan from
 * @param telemetryFile optional production telemetry to infer the workload shape from; without it
 *                      the plan runs as a single-user correctness pass
 */
public record AgentRunRequest(ExecutionMode mode, Path sourceFile, Path telemetryFile) {

    public AgentRunRequest {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");
    }

    /** A run with no telemetry, producing a correctness smoke test rather than a load test. */
    public static AgentRunRequest of(ExecutionMode mode, Path sourceFile) {
        return new AgentRunRequest(mode, sourceFile, null);
    }

    public Optional<Path> telemetry() {
        return Optional.ofNullable(telemetryFile);
    }
}
