package com.ai.jmeter.agent.adapter.cli;

import com.ai.jmeter.agent.domain.SampleFailure;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import java.util.List;
import java.util.Map;

/**
 * What a {@code .jtl} results file revealed about a run.
 *
 * @param totalSamples      how many sampler rows were recorded
 * @param failures          the rows that failed, in the order JMeter wrote them
 * @param statisticsByLabel latency distribution per sampler, the basis of every cross-run
 *                          comparison the analytics layer makes
 */
public record JtlAnalysis(
        int totalSamples,
        List<SampleFailure> failures,
        Map<String, SampleStatistics> statisticsByLabel) {

    public JtlAnalysis {
        failures = List.copyOf(failures);
        statisticsByLabel = Map.copyOf(statisticsByLabel);
    }

    /** Used when JMeter produced no results file at all. */
    public static JtlAnalysis empty() {
        return new JtlAnalysis(0, List.of(), Map.of());
    }
}
