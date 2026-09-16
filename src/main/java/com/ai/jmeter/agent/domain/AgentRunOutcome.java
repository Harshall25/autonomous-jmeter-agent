package com.ai.jmeter.agent.domain;

import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;

/**
 * The successful terminal state of the agentic workflow.
 *
 * <p>Produced only once JMeter has executed the plan with a 100% sampler success rate. The
 * {@code attempts} count records how many generate/execute/heal cycles that took — one means
 * the model got it right first time.
 *
 * @param mode      the ingestion mode the run was driven in
 * @param script    the generation result that finally passed
 * @param artifacts where the passing plan and its test data were written
 * @param report    the clean execution report
 * @param attempts  how many cycles were consumed, including the initial generation
 * @param redaction what was stripped from the capture before it reached the model, retained as
 *                  the compliance record for this run
 * @param cost      what the run spent with the model, for attribution and chargeback
 * @param analysis  what the passing run measured, and how it compares to history
 */
public record AgentRunOutcome(
        ExecutionMode mode,
        JmeterGenerationResult script,
        WorkspaceArtifacts artifacts,
        ExecutionReport report,
        int attempts,
        RedactionResult redaction,
        RunCost cost,
        RunAnalysis analysis) {

    /** @return {@code true} when the plan passed without any self-healing turn. */
    public boolean healedFirstTime() {
        return attempts == 1;
    }
}
