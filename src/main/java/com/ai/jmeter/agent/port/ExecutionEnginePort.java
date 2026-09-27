package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.ExecutionReport;
import java.nio.file.Path;

/**
 * Driven port: the acting half of the agent.
 *
 * <p>Runs a generated plan for real and reports back what happened. The orchestrator treats
 * this as ground truth — the model's confidence in its own output counts for nothing until a
 * run confirms it.
 */
public interface ExecutionEnginePort {

    /**
     * Executes a test plan and analyzes its results.
     *
     * @param jmxScript the plan to run
     * @return the categorized verdict, never {@code null}
     * @throws ExecutionEngineException if the run could not be attempted at all
     */
    ExecutionReport execute(Path jmxScript);
}
