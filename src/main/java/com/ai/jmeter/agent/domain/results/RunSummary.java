package com.ai.jmeter.agent.domain.results;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One run's results, reduced to what is worth keeping across runs.
 *
 * <p>Keyed by a plan fingerprint rather than a run id so that successive runs of the same plan
 * line up into a series. Regression detection is only possible once results are comparable, and
 * results are only comparable once something decides which runs are "the same test".
 *
 * @param runId            unique to this execution
 * @param planFingerprint  stable across runs of the same plan, and the key history is grouped by
 * @param recordedAt       when the run finished
 * @param statisticsByLabel latency distribution per sampler
 * @param concurrentUsers  the thread count this run was executed at
 * @param throughputPerSecond samples per second achieved across the run
 */
public record RunSummary(
        String runId,
        String planFingerprint,
        Instant recordedAt,
        Map<String, SampleStatistics> statisticsByLabel,
        int concurrentUsers,
        double throughputPerSecond) {

    public RunSummary {
        statisticsByLabel = Map.copyOf(statisticsByLabel);
    }

    public long totalSamples() {
        return statisticsByLabel.values().stream().mapToLong(SampleStatistics::count).sum();
    }

    public long totalFailures() {
        return statisticsByLabel.values().stream().mapToLong(SampleStatistics::failures).sum();
    }

    /** @return the slowest sampler by p95, which is where tuning effort belongs. */
    public List<SampleStatistics> slowestSamplers(int limit) {
        return statisticsByLabel.values().stream()
                .sorted((left, right) -> Long.compare(right.p95Millis(), left.p95Millis()))
                .limit(limit)
                .toList();
    }

    public String describe() {
        return """
                Run %s at %s
                  Concurrency : %d user(s)
                  Throughput  : %.2f samples/second
                  Samples     : %d (%d failed)
                  Slowest     : %s""".formatted(
                runId, recordedAt, concurrentUsers, throughputPerSecond,
                totalSamples(), totalFailures(),
                slowestSamplers(3).stream().map(SampleStatistics::describe).toList());
    }
}
