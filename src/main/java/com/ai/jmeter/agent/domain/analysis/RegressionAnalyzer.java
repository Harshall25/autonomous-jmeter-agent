package com.ai.jmeter.agent.domain.analysis;

import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a run is slower than its own history in a way worth blocking a merge over.
 *
 * <p>Judged against the sampler's observed variance rather than a fixed threshold. A fixed
 * threshold is wrong in both directions at once: a 20% rule is noise on an endpoint that swings
 * 40% between runs, and misses a real regression on one that never moves more than 2%. Comparing
 * each sampler to its own spread is what makes the verdict trustworthy enough to gate CI on, which
 * is the only thing that makes performance testing happen before production rather than after.
 *
 * <p>The centre and spread are the median and the median absolute deviation, not the mean and
 * standard deviation. Performance history is full of one-off outliers — a noisy-neighbour run, a
 * cold cache — and a mean-based baseline lets a single bad run poison the comparison for days.
 */
public final class RegressionAnalyzer {

    /**
     * MAD scaled by this approximates the standard deviation for normally distributed data, which
     * is what lets a deviation count be read the usual way.
     */
    private static final double MAD_TO_SIGMA = 1.4826;

    /** Floor on the spread so a perfectly stable sampler does not flag on sub-millisecond noise. */
    private static final double MINIMUM_SPREAD_MILLIS = 2.0;

    private final int minimumHistoricalRuns;
    private final double deviationThreshold;
    private final double minimumChangeRatio;

    /**
     * @param minimumHistoricalRuns runs needed before a baseline is trusted at all
     * @param deviationThreshold    how many robust deviations above the baseline counts as a
     *                              regression
     * @param minimumChangeRatio    a floor on relative change, so a statistically significant but
     *                              operationally meaningless 3ms move does not fail a build
     */
    public RegressionAnalyzer(
            int minimumHistoricalRuns, double deviationThreshold, double minimumChangeRatio) {
        this.minimumHistoricalRuns = minimumHistoricalRuns;
        this.deviationThreshold = deviationThreshold;
        this.minimumChangeRatio = minimumChangeRatio;
    }

    /**
     * Judges every sampler in the current run against its history.
     *
     * @param current the run being judged
     * @param history previous runs of the same plan, most recent first
     * @return one verdict per sampler in the current run
     */
    public List<RegressionVerdict> analyze(RunSummary current, List<RunSummary> history) {
        List<RegressionVerdict> verdicts = new ArrayList<>();

        current.statisticsByLabel().forEach((label, statistics) -> {
            List<Long> baselineSeries = history.stream()
                    .map(run -> run.statisticsByLabel().get(label))
                    .filter(java.util.Objects::nonNull)
                    .map(SampleStatistics::p95Millis)
                    .toList();

            verdicts.add(baselineSeries.size() < minimumHistoricalRuns
                    ? RegressionVerdict.noBaseline(label, statistics.p95Millis())
                    : judge(label, statistics.p95Millis(), baselineSeries));
        });

        verdicts.sort((left, right) ->
                Double.compare(right.deviations(), left.deviations()));
        return List.copyOf(verdicts);
    }

    private RegressionVerdict judge(String label, long current, List<Long> baselineSeries) {
        long baseline = median(baselineSeries);
        double spread = Math.max(MINIMUM_SPREAD_MILLIS,
                medianAbsoluteDeviation(baselineSeries, baseline) * MAD_TO_SIGMA);

        double deviations = (current - baseline) / spread;
        double ratio = baseline == 0 ? 1 : (double) current / baseline;

        boolean significant = deviations >= deviationThreshold;
        boolean material = ratio >= minimumChangeRatio;
        boolean regressed = significant && material;

        return new RegressionVerdict(label, baseline, current, deviations, regressed,
                explain(significant, material, baselineSeries.size()));
    }

    /**
     * Both conditions have to hold, and saying which one failed is what stops an engineer
     * dismissing the whole check as noise.
     */
    private String explain(boolean significant, boolean material, int historicalRuns) {
        if (significant && material) {
            return "outside normal variance over %d run(s) and a material slowdown"
                    .formatted(historicalRuns);
        }
        if (significant) {
            return "outside normal variance, but too small a change to act on";
        }
        if (material) {
            return "a large change, but within this sampler's normal run-to-run swing";
        }
        return "consistent with the last %d run(s)".formatted(historicalRuns);
    }

    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int middle = sorted.size() / 2;
        return sorted.size() % 2 == 1
                ? sorted.get(middle)
                : (sorted.get(middle - 1) + sorted.get(middle)) / 2;
    }

    private static double medianAbsoluteDeviation(List<Long> values, long centre) {
        return median(values.stream().map(value -> Math.abs(value - centre)).toList());
    }
}
