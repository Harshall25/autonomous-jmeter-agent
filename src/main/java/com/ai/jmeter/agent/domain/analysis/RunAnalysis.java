package com.ai.jmeter.agent.domain.analysis;

import com.ai.jmeter.agent.domain.results.RunSummary;
import java.util.List;

/**
 * What a passing run measured, and how it compares to the history of the same plan.
 *
 * @param summary    this run's latency distribution
 * @param verdicts   one per sampler, judged against that sampler's own history
 * @param hypotheses candidate explanations for the slow parts, empty unless diagnosis ran
 */
public record RunAnalysis(
        RunSummary summary,
        List<RegressionVerdict> verdicts,
        List<RootCauseHypothesis> hypotheses) {

    public RunAnalysis {
        verdicts = List.copyOf(verdicts);
        hypotheses = List.copyOf(hypotheses);
    }

    public RunAnalysis(RunSummary summary, List<RegressionVerdict> verdicts) {
        this(summary, verdicts, List.of());
    }

    /** A run recorded before any comparison was possible or requested. */
    public static RunAnalysis withoutComparison(RunSummary summary) {
        return new RunAnalysis(summary, List.of(), List.of());
    }

    /** @return this analysis with diagnosis attached. */
    public RunAnalysis withHypotheses(List<RootCauseHypothesis> diagnosed) {
        return new RunAnalysis(summary, verdicts, diagnosed);
    }

    public List<RegressionVerdict> regressions() {
        return verdicts.stream().filter(RegressionVerdict::regressed).toList();
    }

    /**
     * @return {@code true} when something got materially and unusually slower — the signal a CI
     * gate blocks a merge on
     */
    public boolean hasRegressions() {
        return !regressions().isEmpty();
    }

    public String describe() {
        String base = summary.describe() + "\n" + RegressionVerdict.report(verdicts);
        return hypotheses.isEmpty()
                ? base
                : base + "\n\nRoot-cause hypotheses:\n" + RootCauseHypothesis.report(hypotheses);
    }
}
