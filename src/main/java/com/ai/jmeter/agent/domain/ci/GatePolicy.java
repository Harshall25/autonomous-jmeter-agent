package com.ai.jmeter.agent.domain.ci;

/**
 * What a pipeline is willing to merge.
 *
 * <p>Separate from the regression analysis on purpose: whether a sampler got unusually slower is
 * a statistical question with one right answer, and whether that should block a merge is a policy
 * question each team answers differently. Conflating them is how performance gates end up either
 * ignored or disabled.
 *
 * @param failOnRegression       block when a sampler is materially and unusually slower than its
 *                               own baseline
 * @param failOnHealing          block when the agent had to repair the plan to make it pass; a
 *                               plan that needed healing may be testing something other than what
 *                               the pull request changed
 * @param maxP95Millis           absolute ceiling on any sampler's p95; zero disables it
 * @param maxFailureRatePercent  ceiling on the share of samples allowed to fail; the run itself
 *                               only passes at zero failures, so this guards a relaxed engine
 */
public record GatePolicy(
        boolean failOnRegression,
        boolean failOnHealing,
        long maxP95Millis,
        int maxFailureRatePercent) {

    public GatePolicy {
        if (maxP95Millis < 0) {
            throw new IllegalArgumentException(
                    "maxP95Millis cannot be negative, was " + maxP95Millis);
        }
        maxFailureRatePercent = Math.clamp(maxFailureRatePercent, 0, 100);
    }

    /**
     * @return the default a team gets by adding the gate to a pipeline: block on a statistically
     * significant regression, tolerate a healed plan, and set no absolute ceiling
     */
    public static GatePolicy defaults() {
        return new GatePolicy(true, false, 0, 0);
    }

    /** @return {@code true} when an absolute latency ceiling is in force. */
    public boolean hasLatencyCeiling() {
        return maxP95Millis > 0;
    }

    public String describe() {
        return "Gate policy: regressions %s, healed plans %s, p95 ceiling %s, failure budget %d%%"
                .formatted(
                        failOnRegression ? "block" : "are reported only",
                        failOnHealing ? "block" : "are allowed",
                        hasLatencyCeiling() ? maxP95Millis + "ms" : "none",
                        maxFailureRatePercent);
    }
}
