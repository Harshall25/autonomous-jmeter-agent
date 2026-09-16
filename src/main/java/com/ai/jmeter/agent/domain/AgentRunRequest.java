package com.ai.jmeter.agent.domain;

import com.ai.jmeter.agent.domain.governance.Principal;
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
 * @param requestedBy   who asked, and for which tenant; recorded in the run's provenance, because
 *                      an LLM in the loop makes "who stood behind this plan" a question an
 *                      auditor will eventually ask
 */
public record AgentRunRequest(
        ExecutionMode mode, Path sourceFile, Path telemetryFile, Principal requestedBy) {

    public AgentRunRequest {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");
        requestedBy = requestedBy == null ? Principal.localOperator() : requestedBy;
    }

    /** A run attributed to the local operator, as the command line starts one. */
    public AgentRunRequest(ExecutionMode mode, Path sourceFile, Path telemetryFile) {
        this(mode, sourceFile, telemetryFile, Principal.localOperator());
    }

    /** A run with no telemetry, producing a correctness smoke test rather than a load test. */
    public static AgentRunRequest of(ExecutionMode mode, Path sourceFile) {
        return new AgentRunRequest(mode, sourceFile, null, Principal.localOperator());
    }

    public Optional<Path> telemetry() {
        return Optional.ofNullable(telemetryFile);
    }
}
