package com.ai.jmeter.agent.domain.results;

import java.util.List;

/**
 * Latency distribution for one sampler over one run.
 *
 * <p>Percentiles rather than an average, because an average is the one statistic that reliably
 * hides the failure everyone cares about: a service where 99 requests take 10ms and one takes
 * four seconds averages 50ms and looks healthy.
 *
 * @param label       the sampler this describes
 * @param count       how many samples were recorded
 * @param failures    how many of them failed
 * @param meanMillis  arithmetic mean, kept only because throughput maths needs it
 * @param p50Millis   median
 * @param p95Millis   the usual SLO boundary
 * @param p99Millis   the tail users complain about
 * @param maxMillis   the worst observed
 */
public record SampleStatistics(
        String label,
        long count,
        long failures,
        double meanMillis,
        long p50Millis,
        long p95Millis,
        long p99Millis,
        long maxMillis) {

    /**
     * Computes the distribution from raw elapsed times.
     *
     * @param label   the sampler name
     * @param elapsed every elapsed time recorded, in milliseconds; must not be empty
     * @param failures how many samples failed
     * @return the distribution
     * @throws IllegalArgumentException if no samples were supplied
     */
    public static SampleStatistics of(String label, List<Long> elapsed, long failures) {
        if (elapsed.isEmpty()) {
            throw new IllegalArgumentException("Cannot summarize an empty sample set for " + label);
        }
        List<Long> sorted = elapsed.stream().sorted().toList();
        double mean = sorted.stream().mapToLong(Long::longValue).average().orElseThrow();

        return new SampleStatistics(
                label,
                sorted.size(),
                failures,
                mean,
                percentile(sorted, 50),
                percentile(sorted, 95),
                percentile(sorted, 99),
                sorted.get(sorted.size() - 1));
    }

    /**
     * Nearest-rank percentile: the smallest value at or below which the given share of samples
     * fall. Chosen over interpolation because it always returns a latency that was actually
     * observed, which is what makes a reported p99 defensible in a post-incident review.
     */
    private static long percentile(List<Long> sorted, int percentile) {
        int rank = (int) Math.ceil(percentile / 100.0 * sorted.size());
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, rank - 1)));
    }

    public double failureRate() {
        return (double) failures / count;
    }

    public String describe() {
        return "%s: n=%d p50=%dms p95=%dms p99=%dms max=%dms failures=%d".formatted(
                label, count, p50Millis, p95Millis, p99Millis, maxMillis, failures);
    }
}
