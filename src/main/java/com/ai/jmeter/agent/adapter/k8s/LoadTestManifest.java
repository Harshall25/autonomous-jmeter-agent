package com.ai.jmeter.agent.adapter.k8s;

import java.nio.file.Path;

/**
 * Renders the {@code LoadTest} custom resource that asks the cluster to run a plan.
 *
 * <p>Declarative rather than imperative on purpose: submitting a resource and letting a controller
 * own the pod lifecycle means a load generator that dies mid-run is rescheduled by Kubernetes
 * rather than silently halving the applied load. An adapter that created pods directly would have
 * to reimplement that, badly.
 *
 * <p>Written as plain text rather than through a YAML library so the manifest stays readable in
 * the workspace next to the plan it runs. An operator debugging a failed run wants to see the
 * exact resource that was applied.
 */
public final class LoadTestManifest {

    private LoadTestManifest() {
    }

    /**
     * @param name        the resource name, unique per run
     * @param namespace   where to create it
     * @param image       the JMeter runner image
     * @param workers     how many load-generating pods to fan out to
     * @param jmxScript   the plan, mounted into the workers
     * @param resultsPath where workers write their result shards
     * @param timeoutSeconds how long the controller may let the run take
     * @return the manifest YAML
     */
    public static String render(
            String name,
            String namespace,
            String image,
            int workers,
            Path jmxScript,
            Path resultsPath,
            long timeoutSeconds) {

        return """
                apiVersion: perf.ai.jmeter/v1
                kind: LoadTest
                metadata:
                  name: %s
                  namespace: %s
                  labels:
                    app.kubernetes.io/managed-by: autonomous-jmeter-agent
                spec:
                  image: %s
                  workers: %d
                  timeoutSeconds: %d
                  plan:
                    path: %s
                  results:
                    path: %s
                    # Each worker writes its own shard. Merging them centrally is what makes the
                    # reported percentiles describe the whole run rather than one worker's slice.
                    shardPerWorker: true
                """.formatted(
                name, namespace, image, workers, timeoutSeconds, jmxScript, resultsPath);
    }
}
