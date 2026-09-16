package com.ai.jmeter.agent.adapter.k8s;

import com.ai.jmeter.agent.adapter.cli.JtlAnalysis;
import com.ai.jmeter.agent.adapter.cli.JtlResultParser;
import com.ai.jmeter.agent.adapter.cli.ProcessOutcome;
import com.ai.jmeter.agent.adapter.cli.ProcessRunner;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.port.ExecutionEngineException;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: runs a plan across a fleet of Kubernetes pods instead of one local JVM.
 *
 * <p>A single JMeter process saturates its own CPU well below the concurrency at which enterprise
 * load testing gets interesting — the load generator becomes the bottleneck and the results
 * describe the test harness rather than the system under test. Fanning out across pods moves that
 * ceiling to whatever the cluster can supply.
 *
 * <p>Implements the same {@code ExecutionEnginePort} as the local adapter, so the agentic loop is
 * unchanged: it still validates, executes, reads a verdict and heals. That the port already
 * existed is what makes this an adapter swap rather than a rewrite.
 *
 * <p>Worker shards are concatenated into one results file and parsed by the existing JTL parser
 * rather than merged statistically. Percentiles cannot be averaged across shards — the p95 of two
 * p95s is not a p95 — so the only correct merge is over the raw rows.
 */
public final class KubernetesJmeterAdapter implements ExecutionEnginePort {

    private static final Logger log = LoggerFactory.getLogger(KubernetesJmeterAdapter.class);

    private static final String MERGED_RESULTS_FILE = "results.jtl";
    private static final String MANIFEST_FILE = "loadtest.yaml";
    private static final String APPLY_LOG_FILE = "kubectl-apply.log";
    private static final String WAIT_LOG_FILE = "kubectl-wait.log";

    private final ProcessRunner processRunner;
    private final JtlResultParser jtlResultParser;
    private final KubernetesSettings settings;
    private final Path workspaceDirectory;

    public KubernetesJmeterAdapter(
            ProcessRunner processRunner,
            JtlResultParser jtlResultParser,
            KubernetesSettings settings,
            Path workspaceDirectory) {
        this.processRunner = processRunner;
        this.jtlResultParser = jtlResultParser;
        this.settings = settings;
        this.workspaceDirectory = workspaceDirectory;
    }

    @Override
    public ExecutionReport execute(Path jmxScript) {
        String runName = "jmeter-agent-" + UUID.randomUUID().toString().substring(0, 8);
        Path manifest = writeManifest(runName, jmxScript);

        ProcessOutcome applied = run(
                List.of(settings.kubectlPath(), "apply", "-f", manifest.toString()),
                APPLY_LOG_FILE);
        if (!applied.successful()) {
            return ExecutionReport.processFailure(
                    "Could not submit the LoadTest resource: %s".formatted(
                            readLog(APPLY_LOG_FILE)));
        }

        ProcessOutcome completed = run(
                List.of(settings.kubectlPath(), "wait",
                        "--for=condition=Complete",
                        "loadtest/" + runName,
                        "-n", settings.namespace(),
                        "--timeout=" + settings.timeout().toSeconds() + "s"),
                WAIT_LOG_FILE);

        // The resource is deleted whatever happened: a failed run that leaves its workers behind
        // holds cluster capacity that the next run then cannot get.
        deleteResource(runName);

        if (completed.timedOut() || !completed.successful()) {
            return ExecutionReport.processFailure(
                    "Distributed run did not complete within %s: %s".formatted(
                            settings.timeout(), readLog(WAIT_LOG_FILE)));
        }

        return interpret(mergeShards());
    }

    private ExecutionReport interpret(JtlAnalysis analysis) {
        if (!analysis.failures().isEmpty()) {
            log.warn("{} of {} samples failed across the fleet",
                    analysis.failures().size(), analysis.totalSamples());
            return ExecutionReport.sampleFailures(
                    analysis.totalSamples(), analysis.failures(), "");
        }
        if (analysis.totalSamples() == 0) {
            return ExecutionReport.noSamples(
                    "Workers completed but wrote no result shards to " + settings.resultsPath());
        }
        return ExecutionReport.success(
                analysis.totalSamples(), "", analysis.statisticsByLabel());
    }

    /**
     * Concatenates every worker's shard into one results file, keeping a single header.
     *
     * @return the analysis of the whole fleet's output
     */
    private JtlAnalysis mergeShards() {
        List<Path> shards = findShards();
        if (shards.isEmpty()) {
            return JtlAnalysis.empty();
        }

        Path merged = workspaceDirectory.resolve(MERGED_RESULTS_FILE);
        try {
            List<String> lines = new ArrayList<>();
            for (Path shard : shards) {
                List<String> shardLines = Files.readAllLines(shard, StandardCharsets.UTF_8);
                if (shardLines.isEmpty()) {
                    continue;
                }
                if (lines.isEmpty()) {
                    lines.add(shardLines.get(0));
                }
                lines.addAll(shardLines.subList(1, shardLines.size()));
            }
            Files.write(merged, lines, StandardCharsets.UTF_8);
            log.info("Merged {} worker shard(s) into {}", shards.size(), merged);
        } catch (IOException e) {
            throw new ExecutionEngineException("Unable to merge worker result shards", e);
        }
        return jtlResultParser.parse(merged);
    }

    private List<Path> findShards() {
        try (Stream<Path> files = Files.list(settings.resultsPath())) {
            return files
                    .filter(file -> file.getFileName().toString().endsWith(".jtl"))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        } catch (IOException e) {
            // No readable results directory is indistinguishable from a run that wrote nothing,
            // and both are reported as "no samples" rather than as an infrastructure crash.
            log.warn("Could not list result shards in {}", settings.resultsPath(), e);
            return List.of();
        }
    }

    private Path writeManifest(String runName, Path jmxScript) {
        Path manifest = workspaceDirectory.resolve(MANIFEST_FILE);
        try {
            Files.createDirectories(workspaceDirectory);
            Files.writeString(manifest, LoadTestManifest.render(
                    runName,
                    settings.namespace(),
                    settings.image(),
                    settings.workers(),
                    jmxScript,
                    settings.resultsPath(),
                    settings.timeout().toSeconds()), StandardCharsets.UTF_8);
            return manifest;
        } catch (IOException e) {
            throw new ExecutionEngineException("Unable to write the LoadTest manifest", e);
        }
    }

    private void deleteResource(String runName) {
        run(List.of(settings.kubectlPath(), "delete", "loadtest/" + runName,
                "-n", settings.namespace(), "--ignore-not-found"), "kubectl-delete.log");
    }

    private ProcessOutcome run(List<String> command, String logFile) {
        log.info("Running: {}", String.join(" ", command));
        return processRunner.run(
                command,
                workspaceDirectory,
                workspaceDirectory.resolve(logFile),
                settings.timeout().plus(Duration.ofMinutes(1)));
    }

    private String readLog(String logFile) {
        Path file = workspaceDirectory.resolve(logFile);
        if (!Files.exists(file)) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "<%s could not be read>".formatted(file);
        }
    }
}
