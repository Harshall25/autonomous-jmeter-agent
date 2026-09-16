package com.ai.jmeter.agent.domain.analysis;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Whether a sampler's latency moved enough, relative to its own history, to call it a regression.
 *
 * @param label            the sampler judged
 * @param baselineP95Millis the historical centre, as a median of past runs
 * @param currentP95Millis  this run's p95
 * @param deviations        how many robust standard deviations the current value sits above the
 *                          baseline; the basis of the verdict
 * @param regressed         the verdict
 * @param reason            why, phrased for a pull request comment
 */
public record RegressionVerdict(
        String label,
        long baselineP95Millis,
        long currentP95Millis,
        double deviations,
        boolean regressed,
        String reason) {

    /** A sampler with no history to compare against. */
    public static RegressionVerdict noBaseline(String label, long currentP95Millis) {
        return new RegressionVerdict(label, 0, currentP95Millis, 0, false,
                "No baseline yet; this run establishes one.");
    }

    public double changeRatio() {
        return baselineP95Millis == 0 ? 0 : (double) currentP95Millis / baselineP95Millis;
    }

    public String describe() {
        if (baselineP95Millis == 0) {
            return "%s: p95 %dms (%s)".formatted(label, currentP95Millis, reason);
        }
        return "%s: p95 %dms vs baseline %dms (%.2fx, %.1f deviations) — %s".formatted(
                label, currentP95Millis, baselineP95Millis,
                changeRatio(), deviations, regressed ? "REGRESSED" : "within normal variance");
    }

    /** @return a report over several verdicts, suitable for posting on a pull request. */
    public static String report(List<RegressionVerdict> verdicts) {
        List<RegressionVerdict> regressions = verdicts.stream()
                .filter(RegressionVerdict::regressed)
                .toList();

        String header = regressions.isEmpty()
                ? "No performance regressions detected."
                : "%d performance regression(s) detected:".formatted(regressions.size());

        return header + "\n" + verdicts.stream()
                .map(verdict -> "  " + verdict.describe())
                .collect(Collectors.joining("\n"));
    }
}
