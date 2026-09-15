package com.ai.jmeter.agent.domain;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The instruction that kicks off one agentic workflow.
 *
 * @param mode       which ingestion strategy to apply
 * @param sourceFile the capture to learn the test plan from
 */
public record AgentRunRequest(ExecutionMode mode, Path sourceFile) {

    public AgentRunRequest {
        Objects.requireNonNull(mode, "mode must not be null");
        Objects.requireNonNull(sourceFile, "sourceFile must not be null");
    }
}
