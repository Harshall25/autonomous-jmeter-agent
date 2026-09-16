package com.ai.jmeter.agent.domain.analysis;

/**
 * One observation on the throughput/latency curve: what the system did at a given concurrency.
 *
 * @param concurrency        threads applied
 * @param throughputPerSecond samples per second achieved
 * @param p95Millis          latency at that load
 */
public record SaturationPoint(int concurrency, double throughputPerSecond, long p95Millis) {

    /**
     * @return throughput per unit of concurrency. On a system with headroom this stays flat; it
     * falling away is the first sign of contention, well before latency visibly degrades.
     */
    public double efficiency() {
        return concurrency == 0 ? 0 : throughputPerSecond / concurrency;
    }
}
