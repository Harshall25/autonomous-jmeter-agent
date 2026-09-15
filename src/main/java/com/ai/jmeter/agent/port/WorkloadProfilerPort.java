package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.workload.WorkloadModel;
import java.nio.file.Path;

/**
 * Driven port: infers how much load to apply, and in what proportions, from production telemetry.
 *
 * <p>Separate from {@link TrafficParserPort} on purpose. A parser answers "what requests exist";
 * this answers "how hard, how fast, and in what mix" — and the two come from different sources. A
 * HAR capture shows one user's journey in detail but says nothing about concurrency; an access log
 * shows the shape of a million users but not what any of them sent.
 */
public interface WorkloadProfilerPort {

    /**
     * Infers a workload shape from a telemetry file.
     *
     * @param telemetryFile an access log or APM export
     * @return the inferred shape, or {@link WorkloadModel#smokeTest()} when the sample is too
     * small to infer anything honest from
     * @throws TrafficParsingException if the file cannot be read
     */
    WorkloadModel profile(Path telemetryFile);
}
