package com.ai.jmeter.agent.domain.ci;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.analysis.RegressionVerdict;
import com.ai.jmeter.agent.domain.analysis.RootCauseHypothesis;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Applies a team's policy to a completed run and decides whether the build may go green.
 *
 * <p>Pure: takes the run and the policy, returns the verdict and the comment. Nothing here knows
 * what a pull request is, which is what lets the same decision drive GitHub Actions, GitLab and
 * Jenkins without three copies of the rules drifting apart.
 */
public final class PerformanceGate {

    private final GatePolicy policy;

    public PerformanceGate(GatePolicy policy) {
        this.policy = policy;
    }

    /**
     * @param outcome the run to judge
     * @return whether the build may proceed, and why not if it may not
     */
    public GateVerdict judge(AgentRunOutcome outcome) {
        List<String> reasons = new ArrayList<>();

        if (policy.failOnRegression()) {
            outcome.analysis().regressions().stream()
                    .map(RegressionVerdict::describe)
                    .forEach(reasons::add);
        }
        if (policy.failOnHealing() && !outcome.healedFirstTime()) {
            reasons.add(("The plan needed %d attempt(s) and %s to pass; it may no longer test what "
                    + "the change touched").formatted(
                    outcome.attempts(), outcome.journal().churn()));
        }
        if (policy.hasLatencyCeiling()) {
            outcome.analysis().summary().statisticsByLabel().values().stream()
                    .filter(statistics -> statistics.p95Millis() > policy.maxP95Millis())
                    .map(statistics -> "%s: p95 %dms exceeds the %dms ceiling".formatted(
                            statistics.label(), statistics.p95Millis(), policy.maxP95Millis()))
                    .forEach(reasons::add);
        }
        breachedFailureBudget(outcome).ifPresent(reasons::add);

        return reasons.isEmpty() ? GateVerdict.allowed() : GateVerdict.blocked(reasons);
    }

    /**
     * @return the breach, when more samples failed than the policy allows
     */
    private Optional<String> breachedFailureBudget(AgentRunOutcome outcome) {
        long samples = outcome.analysis().summary().totalSamples();
        if (samples == 0) {
            return Optional.empty();
        }
        double failedPercent = 100.0 * outcome.analysis().summary().totalFailures() / samples;
        if (failedPercent <= policy.maxFailureRatePercent()) {
            return Optional.empty();
        }
        return Optional.of("%.1f%% of samples failed, over the %d%% budget".formatted(
                failedPercent, policy.maxFailureRatePercent()));
    }

    /**
     * Renders the run as a Markdown comment, with a percentile-delta row per sampler.
     *
     * <p>Produced whether the gate passed or failed: a table showing everything held steady is
     * what earns a gate the trust to block a merge on the day it does not.
     *
     * @param outcome the run to report
     * @param verdict the decision that was reached about it
     * @return Markdown, ready to post on a pull request
     */
    public String comment(AgentRunOutcome outcome, GateVerdict verdict) {
        StringBuilder markdown = new StringBuilder();

        markdown.append(verdict.passed()
                        ? "### ✅ Performance gate passed\n\n"
                        : "### ❌ Performance gate failed\n\n")
                .append(summaryLine(outcome))
                .append("\n\n")
                .append(deltaTable(outcome))
                .append('\n');

        if (!verdict.passed()) {
            markdown.append("\n**Why this is blocked**\n\n");
            verdict.reasons().forEach(reason -> markdown.append("- ").append(reason).append('\n'));
        }
        if (!outcome.analysis().hypotheses().isEmpty()) {
            markdown.append("\n<details><summary>Root-cause hypotheses</summary>\n\n```\n")
                    .append(RootCauseHypothesis.report(outcome.analysis().hypotheses()))
                    .append("\n```\n</details>\n");
        }
        if (!outcome.journal().isEmpty()) {
            markdown.append("\n<details><summary>The agent healed this plan (")
                    .append(outcome.journal().churn())
                    .append(")</summary>\n\n```diff\n")
                    .append(healDiffs(outcome))
                    .append("\n```\n</details>\n");
        }
        return markdown.toString();
    }

    private static String summaryLine(AgentRunOutcome outcome) {
        return "`%s` · %d sample(s) · %d attempt(s) · %d token(s)".formatted(
                outcome.mode(),
                outcome.analysis().summary().totalSamples(),
                outcome.attempts(),
                outcome.cost().totalTokens());
    }

    /**
     * One row per sampler: the observed percentiles and, where a baseline exists, how far the p95
     * moved. A delta is what a reviewer reads; an absolute number means nothing without it.
     */
    private static String deltaTable(AgentRunOutcome outcome) {
        StringBuilder table = new StringBuilder(
                "| Sampler | p50 | p95 | p99 | baseline p95 | change |\n"
                        + "| --- | ---: | ---: | ---: | ---: | ---: |\n");

        outcome.analysis().summary().statisticsByLabel().values().stream()
                .sorted((left, right) -> Long.compare(right.p95Millis(), left.p95Millis()))
                .forEach(statistics -> table
                        .append("| ").append(statistics.label())
                        .append(" | ").append(statistics.p50Millis()).append("ms")
                        .append(" | ").append(statistics.p95Millis()).append("ms")
                        .append(" | ").append(statistics.p99Millis()).append("ms")
                        .append(" | ").append(baselineOf(outcome, statistics))
                        .append(" | ").append(changeOf(outcome, statistics))
                        .append(" |\n"));

        return table.toString();
    }

    private static String baselineOf(AgentRunOutcome outcome, SampleStatistics statistics) {
        return verdictFor(outcome, statistics)
                .filter(verdict -> verdict.baselineP95Millis() > 0)
                .map(verdict -> verdict.baselineP95Millis() + "ms")
                .orElse("—");
    }

    private static String changeOf(AgentRunOutcome outcome, SampleStatistics statistics) {
        return verdictFor(outcome, statistics)
                .filter(verdict -> verdict.baselineP95Millis() > 0)
                .map(verdict -> "%s %+.0f%%".formatted(
                        verdict.regressed() ? "⚠️" : "",
                        (verdict.changeRatio() - 1) * 100).strip())
                .orElse("new");
    }

    private static Optional<RegressionVerdict> verdictFor(
            AgentRunOutcome outcome, SampleStatistics statistics) {
        return outcome.analysis().verdicts().stream()
                .filter(verdict -> verdict.label().equals(statistics.label()))
                .findFirst();
    }

    private static String healDiffs(AgentRunOutcome outcome) {
        return String.join("\n\n", outcome.journal().turns().stream()
                .map(turn -> "# after attempt %d — %s\n%s".formatted(
                        turn.attempt(), turn.diagnosis(), turn.diff().render()))
                .toList());
    }
}
