package com.ai.jmeter.agent.domain.eval;

import com.ai.jmeter.agent.domain.ExecutionMode;
import java.nio.file.Path;
import java.util.List;

/**
 * One capture in the golden corpus, and what the agent is expected to make of it.
 *
 * @param name         how this case is reported; must be unique within a suite
 * @param mode         the ingestion mode the capture is read in
 * @param sourceFile   the capture itself
 * @param telemetry    optional access log, when the case also exercises workload inference
 * @param expectations what the produced plan must satisfy
 */
public record EvaluationCase(
        String name,
        ExecutionMode mode,
        Path sourceFile,
        Path telemetry,
        List<StructuralExpectation> expectations) {

    public EvaluationCase {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("An evaluation case must be named");
        }
        expectations = expectations == null ? List.of() : List.copyOf(expectations);
    }

    public String describe() {
        return "%s (%s from %s): %d expectation(s)".formatted(
                name, mode, sourceFile, expectations.size());
    }
}
