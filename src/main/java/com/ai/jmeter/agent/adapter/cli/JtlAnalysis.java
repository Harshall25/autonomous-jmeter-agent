package com.ai.jmeter.agent.adapter.cli;

import com.ai.jmeter.agent.domain.SampleFailure;
import java.util.List;

/**
 * What a {@code .jtl} results file revealed about a run.
 *
 * @param totalSamples how many sampler rows were recorded
 * @param failures     the rows that failed, in the order JMeter wrote them
 */
public record JtlAnalysis(int totalSamples, List<SampleFailure> failures) {

    public JtlAnalysis {
        failures = List.copyOf(failures);
    }

    /** Used when JMeter produced no results file at all. */
    public static JtlAnalysis empty() {
        return new JtlAnalysis(0, List.of());
    }
}
