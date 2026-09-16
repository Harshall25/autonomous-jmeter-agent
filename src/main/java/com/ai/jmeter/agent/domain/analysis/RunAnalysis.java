package com.ai.jmeter.agent.domain.analysis;

import com.ai.jmeter.agent.domain.results.RunSummary;
import java.util.List;

/**
 * What a passing run measured, and how it compares to the history of the same plan.
 *
 * @param summary  this run's latency distribution
 * @param verdicts one per sampler, judged against that sampler's own history
 */
public record RunAnalysis(RunSummary summary, List<RegressionVerdict> verdicts) {

    public RunAnalysis {
        verdicts = List.copyOf(verdicts);
    }

    /** A run recorded before any comparison was possible or requested. */
    public static RunAnalysis withoutComparison(RunSummary summary) {
        return new RunAnalysis(summary, List.of());
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
        return summary.describe() + "\n" + RegressionVerdict.report(verdicts);
    }
}
