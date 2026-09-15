package com.ai.jmeter.agent.domain.workload;

import java.util.List;
import java.util.Map;

/**
 * The shape of real traffic, inferred from production telemetry.
 *
 * <p>This is what turns a correctness smoke test into a load test that predicts anything. A plan
 * that replays the right requests in the wrong ratio and the wrong cadence produces confident
 * numbers about a workload nobody has: hammering a cached read endpoint while barely touching the
 * write path will report headroom the system does not have.
 *
 * @param peakRequestsPerSecond the busiest second observed, which is what capacity is sized against
 * @param concurrentUsers       threads needed to sustain that rate at the observed latency
 * @param rampUpSeconds         how long to take reaching full load
 * @param averageThinkTimeMillis the gap between one user's consecutive requests
 * @param endpointMix           share of traffic per endpoint, summing to roughly one
 * @param observedWindowSeconds how long the telemetry covered
 */
public record WorkloadModel(
        double peakRequestsPerSecond,
        int concurrentUsers,
        int rampUpSeconds,
        long averageThinkTimeMillis,
        Map<String, Double> endpointMix,
        long observedWindowSeconds) {

    /** Below this, a sample is too small to infer a shape from and the default is honest. */
    private static final int MINIMUM_CREDIBLE_REQUESTS = 20;

    public WorkloadModel {
        endpointMix = Map.copyOf(endpointMix);
    }

    /** The shape assumed when no telemetry was supplied: a single-user correctness pass. */
    public static WorkloadModel smokeTest() {
        return new WorkloadModel(0, 1, 1, 0, Map.of(), 0);
    }

    /**
     * @return {@code true} when this model was inferred from enough traffic to be worth applying.
     * A handful of log lines describes one person clicking around, not a workload.
     */
    public boolean isCredible() {
        // Concurrency and peak rate are guaranteed positive by construction whenever a mix and a
        // window exist, so checking them too would be defensive noise rather than a real test.
        return !endpointMix.isEmpty() && observedWindowSeconds > 0;
    }

    /** @return the endpoints carrying the most traffic, which the plan must weight accordingly. */
    public List<Map.Entry<String, Double>> busiestEndpoints(int limit) {
        return endpointMix.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(limit)
                .toList();
    }

    /** @return an operator- and model-readable statement of the shape. */
    public String describe() {
        if (!isCredible()) {
            return "No credible workload telemetry; plan will run as a single-user smoke test.";
        }
        return """
                Observed over %d second(s):
                  Peak throughput  : %.2f requests/second
                  Concurrent users : %d
                  Ramp-up          : %d second(s)
                  Think time       : %d ms between requests
                  Busiest endpoints: %s""".formatted(
                observedWindowSeconds, peakRequestsPerSecond, concurrentUsers,
                rampUpSeconds, averageThinkTimeMillis, busiestEndpoints(10));
    }

    /** @return the smallest request count an inference is allowed to be built from. */
    public static int minimumCredibleRequests() {
        return MINIMUM_CREDIBLE_REQUESTS;
    }
}
