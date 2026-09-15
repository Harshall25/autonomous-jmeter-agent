package com.ai.jmeter.agent.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.ExecutionStatus;
import com.ai.jmeter.agent.port.ExecutionEngineException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
 * Exercises the execution adapter with a stubbed {@link ProcessRunner} that writes the files a
 * real JMeter run would leave behind, so the verdict logic is tested without a JMeter install.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ProcessBuilderJmeterAdapter")
class ProcessBuilderJmeterAdapterTest {

    private static final String HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,"
                    + "success,failureMessage";
    private static final String PASSING_ROW = "1,12,login,200,OK,T,text,true,";
    private static final String FAILING_ROW = "1,12,login,401,Unauthorized,T,text,false,no token";

    @Mock
    private ProcessRunner processRunner;

    @TempDir
    Path workspace;

    private Path jmxScript;
    private ProcessBuilderJmeterAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        jmxScript = workspace.resolve("auto_test.jmx");
        Files.writeString(jmxScript, "<jmeterTestPlan/>");
        adapter = new ProcessBuilderJmeterAdapter(
                processRunner,
                new JtlResultParser(500),
                Path.of("/opt/jmeter/bin"),
                workspace,
                Duration.ofMinutes(10),
                8000);
    }

    /** Stubs a run that leaves the given results and console output behind. */
    private void stubRun(ProcessOutcome outcome, String jtlContent, String consoleOutput) {
        when(processRunner.run(anyList(), any(), any(), any())).thenAnswer(invocation -> {
            if (jtlContent != null) {
                Files.writeString(workspace.resolve("results.jtl"), jtlContent);
            }
            if (consoleOutput != null) {
                Files.writeString(invocation.getArgument(2), consoleOutput);
            }
            return outcome;
        });
    }

    @Test
    @DisplayName("invokes JMeter in non-GUI mode with the plan and results paths")
    void buildsNonGuiCommand() {
        stubRun(ProcessOutcome.completed(0), HEADER + "\n" + PASSING_ROW, "");

        adapter.execute(jmxScript);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(processRunner).run(command.capture(), any(), any(), any());

        assertThat(command.getValue())
                .containsSubsequence("-n", "-t", jmxScript.toString())
                .containsSubsequence("-l", workspace.resolve("results.jtl").toString())
                .contains("-f")
                .first(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .endsWith("jmeter");
    }

    @Test
    @DisplayName("binds redacted credentials back only when a secrets file exists")
    void passesSecretsFileWhenPresent() throws IOException {
        Files.writeString(workspace.resolve("secrets.properties"), "agent.secret.credential.1=x");
        stubRun(ProcessOutcome.completed(0), HEADER + "\n" + PASSING_ROW, "");

        adapter.execute(jmxScript);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(processRunner).run(command.capture(), any(), any(), any());
        assertThat(command.getValue())
                .containsSubsequence("-q", workspace.resolve("secrets.properties").toString());
    }

    @Test
    @DisplayName("omits the properties flag when nothing was redacted")
    void omitsSecretsFileWhenAbsent() {
        stubRun(ProcessOutcome.completed(0), HEADER + "\n" + PASSING_ROW, "");

        adapter.execute(jmxScript);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> command = ArgumentCaptor.forClass(List.class);
        verify(processRunner).run(command.capture(), any(), any(), any());
        assertThat(command.getValue()).doesNotContain("-q");
    }

    @Test
    @DisplayName("passes a run where JMeter recorded samples and no failures")
    void reportsSuccess() {
        stubRun(ProcessOutcome.completed(0), HEADER + "\n" + PASSING_ROW, "Tidying up ...");

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.successful()).isTrue();
        assertThat(report.totalSamples()).isEqualTo(1);
        assertThat(report.processOutput()).contains("Tidying up");
    }

    @Test
    @DisplayName("reports failing samples as the richest evidence available")
    void reportsSampleFailures() {
        stubRun(ProcessOutcome.completed(0), HEADER + "\n" + FAILING_ROW, "");

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.status()).isEqualTo(ExecutionStatus.SAMPLE_FAILURE);
        assertThat(report.failedSamples()).isEqualTo(1);
        assertThat(report.errorDigest()).contains("responseCode=401");
    }

    @Test
    @DisplayName("prefers failing samples over a non-zero exit code")
    void prefersSampleFailuresOverExitCode() {
        // The exit code says only that something went wrong; the failing rows say what.
        stubRun(ProcessOutcome.completed(1), HEADER + "\n" + FAILING_ROW, "");

        assertThat(adapter.execute(jmxScript).status()).isEqualTo(ExecutionStatus.SAMPLE_FAILURE);
    }

    @Test
    @DisplayName("reports a non-zero exit with no results as a broken plan")
    void reportsProcessFailure() {
        stubRun(ProcessOutcome.completed(1), null, "Error in NonGUIDriver: invalid XML at line 4");

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.status()).isEqualTo(ExecutionStatus.PROCESS_FAILURE);
        assertThat(report.processOutput()).contains("invalid XML at line 4");
        assertThat(report.errorDigest()).contains("JMeter exited with status 1");
    }

    @Test
    @DisplayName("reports a clean exit that exercised nothing")
    void reportsNoSamples() {
        stubRun(ProcessOutcome.completed(0), HEADER + "\n", "Nothing to run");

        assertThat(adapter.execute(jmxScript).status()).isEqualTo(ExecutionStatus.NO_SAMPLES);
    }

    @Test
    @DisplayName("reports a timeout, discarding whatever partial results were written")
    void reportsTimeout() {
        stubRun(ProcessOutcome.killedOnTimeout(), HEADER + "\n" + PASSING_ROW, "hung");

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.status()).isEqualTo(ExecutionStatus.PROCESS_FAILURE);
        assertThat(report.processOutput())
                .contains("JMeter timed out after PT10M")
                .contains("hung");
    }

    @Test
    @DisplayName("clears a stale results file so a rerun is never read as this run")
    void clearsStaleResults() throws IOException {
        Path staleResults = workspace.resolve("results.jtl");
        Files.writeString(staleResults, HEADER + "\n" + FAILING_ROW);
        stubRun(ProcessOutcome.completed(0), HEADER + "\n" + PASSING_ROW, "");

        ExecutionReport report = adapter.execute(jmxScript);

        assertThat(report.successful())
                .as("the previous run's 401 must not leak into this verdict")
                .isTrue();
    }

    @Test
    @DisplayName("reports a results file that cannot be cleared")
    void reportsUnclearableResults() throws IOException {
        Path blocked = workspace.resolve("results.jtl");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("occupant.txt"), "in the way");

        assertThatThrownBy(() -> adapter.execute(jmxScript))
                .isInstanceOf(ExecutionEngineException.class)
                .hasMessageContaining("Unable to clear stale results file");
    }

    @Test
    @DisplayName("copes with a run that produced no console log")
    void copesWithMissingRunLog() {
        stubRun(ProcessOutcome.completed(0), HEADER + "\n" + PASSING_ROW, null);

        assertThat(adapter.execute(jmxScript).processOutput()).isEmpty();
    }

    @Test
    @DisplayName("keeps the tail of a large console log, where JMeter reports its errors")
    void truncatesLargeConsoleOutput() {
        String noise = "n".repeat(500);
        stubRun(ProcessOutcome.completed(1), null, noise + "the actual error");

        ProcessBuilderJmeterAdapter smallBudget = new ProcessBuilderJmeterAdapter(
                processRunner, new JtlResultParser(500), Path.of("/opt/jmeter/bin"),
                workspace, Duration.ofMinutes(10), 40);

        assertThat(smallBudget.execute(jmxScript).processOutput())
                .contains("the actual error")
                .contains("[earlier output truncated]")
                .doesNotContain(noise);
    }

    @Test
    @DisplayName("degrades gracefully when the console log cannot be read")
    void copesWithUnreadableRunLog() {
        when(processRunner.run(anyList(), any(), any(), any())).thenAnswer(invocation -> {
            Files.createDirectory(invocation.getArgument(2));
            return ProcessOutcome.completed(1);
        });

        assertThat(adapter.execute(jmxScript).processOutput())
                .contains("could not be read");
    }
}
