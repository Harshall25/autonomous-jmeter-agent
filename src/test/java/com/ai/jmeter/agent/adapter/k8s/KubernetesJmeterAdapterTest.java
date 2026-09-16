package com.ai.jmeter.agent.adapter.k8s;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.adapter.cli.JtlResultParser;
import com.ai.jmeter.agent.adapter.cli.ProcessOutcome;
import com.ai.jmeter.agent.adapter.cli.ProcessRunner;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.ExecutionStatus;
import com.ai.jmeter.agent.port.ExecutionEngineException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Exercises the distributed engine with a stubbed process runner, so command construction, shard
 * merging and cleanup are verified without a cluster.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("KubernetesJmeterAdapter")
class KubernetesJmeterAdapterTest {

    private static final String HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,"
                    + "success,failureMessage";

    @Mock
    private ProcessRunner processRunner;

    @TempDir
    Path workspace;

    private Path shards;
    private Path jmxScript;
    private KubernetesJmeterAdapter adapter;
    private final List<List<String>> commands = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        shards = Files.createDirectories(workspace.resolve("shards"));
        jmxScript = workspace.resolve("auto_test.jmx");
        Files.writeString(jmxScript, "<jmeterTestPlan/>");

        adapter = new KubernetesJmeterAdapter(
                processRunner,
                new JtlResultParser(500),
                new KubernetesSettings(
                        "kubectl", "perf", "jmeter:5.6", 4, shards, Duration.ofMinutes(5)),
                workspace);

        commands.clear();
        when(processRunner.run(anyList(), any(), any(), any())).thenAnswer(invocation -> {
            commands.add(invocation.getArgument(0));
            return ProcessOutcome.completed(0);
        });
    }

    private void writeShard(String name, String... rows) throws IOException {
        Files.writeString(shards.resolve(name), HEADER + "\n" + String.join("\n", rows) + "\n");
    }

    @Test
    @DisplayName("submits a LoadTest resource rather than creating pods itself")
    void submitsLoadTestResource() throws IOException {
        // Letting a controller own the pod lifecycle means a load generator that dies mid-run is
        // rescheduled rather than silently halving the applied load.
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");

        adapter.execute(jmxScript);

        assertThat(commands.get(0)).containsSubsequence("kubectl", "apply", "-f");
        assertThat(Files.readString(workspace.resolve("loadtest.yaml")))
                .contains("kind: LoadTest")
                .contains("namespace: perf")
                .contains("workers: 4")
                .contains("image: jmeter:5.6")
                .contains(jmxScript.toString());
    }

    @Test
    @DisplayName("waits for the run to complete before reading results")
    void waitsForCompletion() throws IOException {
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");

        adapter.execute(jmxScript);

        assertThat(commands.get(1))
                .containsSubsequence("kubectl", "wait", "--for=condition=Complete")
                .containsSubsequence("-n", "perf")
                .anyMatch(argument -> argument.startsWith("--timeout="));
    }

    @Test
    @DisplayName("merges every worker's shard so the percentiles describe the whole fleet")
    void mergesShards() throws IOException {
        // The p95 of two p95s is not a p95, so the only correct merge is over the raw rows.
        writeShard("worker-0.jtl",
                "1,10,login,200,OK,T,text,true,", "1,20,login,200,OK,T,text,true,");
        writeShard("worker-1.jtl",
                "1,30,login,200,OK,T,text,true,", "1,40,login,200,OK,T,text,true,");

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.successful()).isTrue();
        assertThat(report.totalSamples()).isEqualTo(4);
        assertThat(report.statisticsByLabel().get("login").maxMillis()).isEqualTo(40);
    }

    @Test
    @DisplayName("keeps a single header when merging shards")
    void mergedFileHasOneHeader() throws IOException {
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");
        writeShard("worker-1.jtl", "1,20,login,200,OK,T,text,true,");

        adapter.execute(jmxScript);

        assertThat(Files.readAllLines(workspace.resolve("results.jtl")))
                .hasSize(3)
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .startsWith("timeStamp");
    }

    @Test
    @DisplayName("reports failing samples gathered from across the fleet")
    void reportsFleetFailures() throws IOException {
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");
        writeShard("worker-1.jtl", "1,12,orders,401,Unauthorized,T,text,false,no token");

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.status()).isEqualTo(ExecutionStatus.SAMPLE_FAILURE);
        assertThat(report.errorDigest()).contains("responseCode=401");
    }

    @Test
    @DisplayName("always deletes the resource, so a failed run does not hold cluster capacity")
    void alwaysDeletesTheResource() throws IOException {
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");

        adapter.execute(jmxScript);

        assertThat(commands)
                .anySatisfy(command -> assertThat(command)
                        .containsSubsequence("kubectl", "delete")
                        .contains("--ignore-not-found"));
    }

    @Test
    @DisplayName("reports a submission the cluster rejected")
    void reportsFailedSubmission() throws IOException {
        Files.writeString(workspace.resolve("kubectl-apply.log"), "no such kind: LoadTest");
        when(processRunner.run(anyList(), any(), any(), any()))
                .thenReturn(ProcessOutcome.completed(1));

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.status()).isEqualTo(ExecutionStatus.PROCESS_FAILURE);
        assertThat(report.processOutput()).contains("Could not submit the LoadTest resource");
    }

    @Test
    @DisplayName("reports a run that never completed")
    void reportsIncompleteRun() {
        when(processRunner.run(anyList(), any(), any(), any()))
                .thenReturn(ProcessOutcome.completed(0))
                .thenReturn(ProcessOutcome.killedOnTimeout());

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.status()).isEqualTo(ExecutionStatus.PROCESS_FAILURE);
        assertThat(report.processOutput()).contains("did not complete within");
    }

    @Test
    @DisplayName("reports a wait that failed for a reason other than a timeout")
    void reportsFailedWait() {
        when(processRunner.run(anyList(), any(), any(), any()))
                .thenReturn(ProcessOutcome.completed(0))
                .thenReturn(ProcessOutcome.completed(1));

        assertThat(adapter.execute(jmxScript).status())
                .isEqualTo(ExecutionStatus.PROCESS_FAILURE);
    }

    @Test
    @DisplayName("reports workers that completed but measured nothing")
    void reportsNoShards() {
        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.status()).isEqualTo(ExecutionStatus.NO_SAMPLES);
        assertThat(report.processOutput()).contains("no result shards");
    }

    @Test
    @DisplayName("treats an unreadable results directory as a run that measured nothing")
    void unreadableResultsDirectory() {
        KubernetesJmeterAdapter missingResults = new KubernetesJmeterAdapter(
                processRunner,
                new JtlResultParser(500),
                new KubernetesSettings("kubectl", "perf", "jmeter:5.6", 4,
                        workspace.resolve("never-created"), Duration.ofMinutes(5)),
                workspace);

        assertThat(missingResults.execute(jmxScript).status())
                .isEqualTo(ExecutionStatus.NO_SAMPLES);
    }

    @Test
    @DisplayName("ignores a shard a worker left empty")
    void ignoresEmptyShards() throws IOException {
        Files.writeString(shards.resolve("worker-0.jtl"), "");
        writeShard("worker-1.jtl", "1,10,login,200,OK,T,text,true,");

        assertThat(adapter.execute(jmxScript).totalSamples()).isEqualTo(1);
    }

    @Test
    @DisplayName("ignores files in the results directory that are not shards")
    void ignoresNonShardFiles() throws IOException {
        Files.writeString(shards.resolve("README.txt"), "not a shard");
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");

        assertThat(adapter.execute(jmxScript).totalSamples()).isEqualTo(1);
    }

    @Test
    @DisplayName("reports a workspace the manifest cannot be written to")
    void reportsUnwritableWorkspace() throws IOException {
        Path blocked = workspace.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        KubernetesJmeterAdapter brittle = new KubernetesJmeterAdapter(
                processRunner,
                new JtlResultParser(500),
                new KubernetesSettings(
                        "kubectl", "perf", "jmeter:5.6", 4, shards, Duration.ofMinutes(5)),
                blocked.resolve("nested"));

        assertThatThrownBy(() -> brittle.execute(jmxScript))
                .isInstanceOf(ExecutionEngineException.class)
                .hasMessageContaining("Unable to write the LoadTest manifest");
    }

    @Test
    @DisplayName("gives each run its own resource name so concurrent runs do not collide")
    void runNamesAreUnique() throws IOException {
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");

        adapter.execute(jmxScript);
        String first = Files.readString(workspace.resolve("loadtest.yaml"));
        adapter.execute(jmxScript);
        String second = Files.readString(workspace.resolve("loadtest.yaml"));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    @DisplayName("allows the run the configured budget plus headroom for cluster scheduling")
    void allowsSchedulingHeadroom() throws IOException {
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");

        adapter.execute(jmxScript);

        ArgumentCaptor<Duration> timeout = ArgumentCaptor.forClass(Duration.class);
        verify(processRunner, org.mockito.Mockito.atLeastOnce())
                .run(anyList(), any(), any(), timeout.capture());
        assertThat(timeout.getValue()).isGreaterThan(Duration.ofMinutes(5));
    }

    @Test
    @DisplayName("refuses a fleet with no workers in it")
    void refusesZeroWorkers() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new KubernetesSettings(
                        "kubectl", "perf", "jmeter:5.6", 0, shards, Duration.ofMinutes(5)))
                .withMessageContaining("workers must be at least 1");
    }

    @Test
    @DisplayName("reports shards that cannot be merged")
    void reportsUnmergeableShards() throws IOException {
        writeShard("worker-0.jtl", "1,10,login,200,OK,T,text,true,");
        Files.createDirectory(workspace.resolve("results.jtl"));

        assertThatThrownBy(() -> adapter.execute(jmxScript))
                .isInstanceOf(ExecutionEngineException.class)
                .hasMessageContaining("Unable to merge worker result shards");
    }

    @Test
    @DisplayName("degrades gracefully when a kubectl log cannot be read")
    void copesWithUnreadableLog() throws IOException {
        Files.createDirectory(workspace.resolve("kubectl-apply.log"));
        when(processRunner.run(anyList(), any(), any(), any()))
                .thenReturn(ProcessOutcome.completed(1));

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.processOutput()).contains("could not be read");
    }
}
