package com.ai.jmeter.agent.adapter.k8s;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Where and how the distributed execution adapter runs a plan.
 *
 * @param kubectlPath  the CLI to drive the cluster with
 * @param namespace    where the LoadTest resource is created
 * @param image        the JMeter runner image the workers run
 * @param workers      how many load-generating pods to fan out to
 * @param resultsPath  the shared volume the workers write their shards to, as this process sees it
 * @param timeout      how long a distributed run may take before it is abandoned
 */
public record KubernetesSettings(
        String kubectlPath,
        String namespace,
        String image,
        int workers,
        Path resultsPath,
        Duration timeout) {

    public KubernetesSettings {
        if (workers < 1) {
            throw new IllegalArgumentException("workers must be at least 1, was " + workers);
        }
    }
}
