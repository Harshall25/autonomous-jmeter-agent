package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.analysis.RootCauseHypothesis;
import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import java.util.List;

/**
 * Driven port: explains why a run was slow, rather than only reporting that it was.
 *
 * <p>The natural extension of an agent that already reasons over execution evidence to repair a
 * plan: the same reasoning applied to the results. Diagnosis is the least-automated and most
 * expensive part of performance engineering — the numbers are easy to produce and hard to act on,
 * and a senior engineer currently spends days turning one into the other.
 */
public interface RootCauseAnalyzerPort {

    /**
     * Proposes ranked explanations for the slow parts of a run.
     *
     * @param analysis what the run measured and how it compared to history
     * @return hypotheses with evidence attached, most confident first; empty when the run offers
     * nothing worth explaining
     */
    List<RootCauseHypothesis> explain(RunAnalysis analysis);
}
