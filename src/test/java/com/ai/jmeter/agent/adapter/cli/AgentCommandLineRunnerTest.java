package com.ai.jmeter.agent.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import com.ai.jmeter.agent.domain.ci.GatePolicy;
import com.ai.jmeter.agent.domain.ci.GateVerdict;
import com.ai.jmeter.agent.domain.ci.PerformanceGate;
import com.ai.jmeter.agent.domain.ci.PerformanceGateFailedException;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.journal.HealJournal;
import com.ai.jmeter.agent.domain.results.RunSummary;
import java.time.Instant;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
import com.ai.jmeter.agent.port.BuildReporterPort;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("AgentCommandLineRunner")
class AgentCommandLineRunnerTest {

    @Mock
    private SelfHealingOrchestrator orchestrator;

    @Mock
    private BuildReporterPort buildReporter;

    private AgentCommandLineRunner runner() {
        return runner(false, GatePolicy.defaults());
    }

    private AgentCommandLineRunner runner(boolean gateEnabled, GatePolicy policy) {
        return new AgentCommandLineRunner(
                orchestrator, new PerformanceGate(policy), buildReporter, gateEnabled);
    }

    private static RunSummary summary() {
        return new RunSummary("run-1", "fp", Instant.EPOCH, java.util.Map.of(), 1, 0);
    }

    private void stubSuccessfulRun() {
        when(orchestrator.run(any())).thenReturn(new AgentRunOutcome(
                ExecutionMode.API,
                new JmeterGenerationResult("<plan/>", "user", List.of("user"), "all good"),
                new WorkspaceArtifacts(Path.of("workspace/auto_test.jmx"),
                        Path.of("workspace/test_data.csv")),
                ExecutionReport.success(4, ""),
                2,
                RedactionResult.clean("[]"),
                RunCost.empty(0),
                RunAnalysis.withoutComparison(summary()),
                HealJournal.empty()));
    }

    @Test
    @DisplayName("starts a run when both arguments are supplied")
    void runsWithBothArguments() {
        stubSuccessfulRun();

        runner().run("--mode=API", "--source=/captures/checkout.har");

        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(orchestrator).run(request.capture());
        assertThat(request.getValue().mode()).isEqualTo(ExecutionMode.API);
        assertThat(request.getValue().sourceFile()).isEqualTo(Path.of("/captures/checkout.har"));
    }

    @Test
    @DisplayName("accepts the mode in any casing")
    void acceptsLowercaseMode() {
        stubSuccessfulRun();

        runner().run("--mode=sql", "--source=slow.log");

        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(orchestrator).run(request.capture());
        assertThat(request.getValue().mode()).isEqualTo(ExecutionMode.SQL);
    }

    @Test
    @DisplayName("passes telemetry through when a workload log is supplied")
    void forwardsWorkloadArgument() {
        stubSuccessfulRun();

        runner().run("--mode=API", "--source=a.har", "--workload=/logs/access.log");

        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(orchestrator).run(request.capture());
        assertThat(request.getValue().telemetry())
                .contains(Path.of("/logs/access.log"));
    }

    @Test
    @DisplayName("runs without telemetry when no workload log is supplied")
    void omitsWorkloadWhenAbsent() {
        stubSuccessfulRun();

        runner().run("--mode=API", "--source=a.har");

        ArgumentCaptor<AgentRunRequest> request = ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(orchestrator).run(request.capture());
        assertThat(request.getValue().telemetry()).isEmpty();
    }

    @Test
    @DisplayName("ignores arguments it does not recognize")
    void ignoresUnknownArguments() {
        stubSuccessfulRun();

        runner().run("--spring.profiles.active=dev", "--mode=API", "--source=a.har");

        verify(orchestrator).run(any());
    }

    @Test
    @DisplayName("prints usage instead of running when started with no arguments")
    void printsUsageWithoutArguments() {
        runner().run();

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("prints usage when the source is missing")
    void printsUsageWithoutSource() {
        runner().run("--mode=API");

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("prints usage when the mode is missing")
    void printsUsageWithoutMode() {
        runner().run("--source=a.har");

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("treats an empty argument value as missing")
    void treatsBlankValueAsMissing() {
        runner().run("--mode=", "--source=a.har");

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("leaves an evaluation run to the runner that owns it")
    void staysQuietForAnEvaluationRun() {
        runner().run("--evaluate=corpus/suite.yaml");

        verifyNoInteractions(orchestrator);
    }

    @Test
    @DisplayName("names the valid modes when given one it does not know")
    void rejectsUnknownMode() {
        AgentCommandLineRunner runner = runner();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> runner.run("--mode=GRPC", "--source=a.har"))
                .withMessageContaining("Unknown mode 'GRPC'")
                .withMessageContaining("API")
                .withMessageContaining("SQL");
    }

    @Nested
    @DisplayName("the CI performance gate")
    class Gating {

        @Test
        @DisplayName("stays out of the way until a pipeline asks for it")
        void offByDefault() {
            stubSuccessfulRun();

            runner().run("--mode=API", "--source=a.har");

            verifyNoInteractions(buildReporter);
        }

        @Test
        @DisplayName("publishes the report and lets the build through when nothing moved")
        void publishesAPassingReport() {
            stubSuccessfulRun();

            runner(true, GatePolicy.defaults()).run("--mode=API", "--source=a.har");

            ArgumentCaptor<GateVerdict> verdict = ArgumentCaptor.forClass(GateVerdict.class);
            ArgumentCaptor<String> comment = ArgumentCaptor.forClass(String.class);
            verify(buildReporter).publish(verdict.capture(), comment.capture());
            assertThat(verdict.getValue().passed()).isTrue();
            assertThat(comment.getValue()).contains("Performance gate passed");
        }

        @Test
        @DisplayName("fails the process with its own exit code when the policy is breached")
        void failsTheBuildOnABreach() {
            stubSuccessfulRun();
            AgentCommandLineRunner runner = runner(true, new GatePolicy(true, true, 0, 0));

            assertThatThrownBy(() -> runner.run("--mode=API", "--source=a.har"))
                    .isInstanceOf(PerformanceGateFailedException.class)
                    .satisfies(thrown -> assertThat(
                            ((PerformanceGateFailedException) thrown).verdict().exitCode())
                            .isEqualTo(2));
        }

        @Test
        @DisplayName("publishes the report before failing, so the block is explained")
        void publishesBeforeFailing() {
            // A blocked build with no table is a blocked build somebody switches the gate off for.
            stubSuccessfulRun();
            AgentCommandLineRunner runner = runner(true, new GatePolicy(true, true, 0, 0));

            assertThatThrownBy(() -> runner.run("--mode=API", "--source=a.har"))
                    .isInstanceOf(PerformanceGateFailedException.class);

            verify(buildReporter).publish(any(), anyString());
        }

        @Test
        @DisplayName("never reaches the gate when there was no run to judge")
        void noRunMeansNoGate() {
            runner(true, GatePolicy.defaults()).run("--mode=API");

            verifyNoInteractions(buildReporter);
        }
    }
}
