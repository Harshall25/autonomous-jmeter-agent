package com.ai.jmeter.agent.domain.eval;

import java.util.List;

/**
 * The corpus reduced to the four numbers that decide whether a prompt revision shipped.
 *
 * <p>Prompt text is behaviour. Without this, editing a system prompt or upgrading a model can
 * degrade the agent with nothing failing and nobody noticing until a customer's plan starts
 * coming back wrong. Scoring the same captures on every revision makes that degradation a number
 * that moved.
 *
 * @param revision              what was being evaluated: the prompt revision and model in use
 * @param cases                 every case's result, in corpus order
 */
public record EvaluationScore(String revision, List<CaseResult> cases) {

    public EvaluationScore {
        revision = revision == null ? "" : revision;
        cases = List.copyOf(cases);
    }

    public int caseCount() {
        return cases.size();
    }

    /**
     * @return the share of cases the model got right with no repair turn at all — the headline
     * number, because every heal cycle is latency and tokens a user pays for
     */
    public double firstAttemptSuccessRate() {
        return rateOf(cases.stream().filter(CaseResult::firstAttemptSuccess).count());
    }

    /** @return the share of cases that produced a passing plan at all. */
    public double completionRate() {
        return rateOf(cases.stream().filter(CaseResult::completed).count());
    }

    /**
     * @return the share of cases whose plan met every structural expectation — the number that
     * catches a plan which runs green while testing the wrong thing
     */
    public double correctnessRate() {
        return rateOf(cases.stream().filter(CaseResult::fullyCorrect).count());
    }

    /** @return repairs spent per case on average. */
    public double meanHealCycles() {
        return cases.isEmpty() ? 0 : average(cases.stream().mapToLong(CaseResult::healCycles).sum());
    }

    /** @return tokens spent per case on average, for cost-per-revision comparison. */
    public double meanTokenCost() {
        return cases.isEmpty() ? 0 : average(cases.stream().mapToLong(CaseResult::tokens).sum());
    }

    /**
     * Compares this score against the revision it is meant to improve on.
     *
     * @param baseline the score of the revision currently in production
     * @return every metric that got worse, phrased for a report; empty when nothing regressed
     */
    public List<String> regressionsAgainst(EvaluationScore baseline) {
        return List.of(
                        compare("first-attempt success", firstAttemptSuccessRate(),
                                baseline.firstAttemptSuccessRate(), true),
                        compare("correctness", correctnessRate(),
                                baseline.correctnessRate(), true),
                        compare("completion", completionRate(),
                                baseline.completionRate(), true),
                        compare("mean heal cycles", meanHealCycles(),
                                baseline.meanHealCycles(), false),
                        compare("mean token cost", meanTokenCost(),
                                baseline.meanTokenCost(), false))
                .stream()
                .filter(finding -> !finding.isEmpty())
                .toList();
    }

    /**
     * @param higherIsBetter true for a rate, false for a cost
     * @return the finding, or empty when the metric did not get worse
     */
    private static String compare(
            String metric, double current, double baseline, boolean higherIsBetter) {
        boolean worse = higherIsBetter ? current < baseline : current > baseline;
        if (!worse) {
            return "";
        }
        return "%s got worse: %.2f, was %.2f".formatted(metric, current, baseline);
    }

    private double rateOf(long matching) {
        return cases.isEmpty() ? 0 : (double) matching / cases.size();
    }

    private double average(long total) {
        return (double) total / cases.size();
    }

    public String describe() {
        return """
                Evaluation of %s over %d case(s)
                  First-attempt success : %.0f%%
                  Fully correct         : %.0f%%
                  Completed at all      : %.0f%%
                  Mean heal cycles      : %.2f
                  Mean token cost       : %.0f

                %s""".formatted(
                revision, caseCount(),
                firstAttemptSuccessRate() * 100,
                correctnessRate() * 100,
                completionRate() * 100,
                meanHealCycles(),
                meanTokenCost(),
                String.join(System.lineSeparator(),
                        cases.stream().map(result -> "  " + result.describe()).toList()));
    }
}
