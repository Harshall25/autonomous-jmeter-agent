package com.ai.jmeter.agent.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
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

    private AgentCommandLineRunner runner() {
        return new AgentCommandLineRunner(orchestrator);
    }

    private void stubSuccessfulRun() {
        when(orchestrator.run(any())).thenReturn(new AgentRunOutcome(
                ExecutionMode.API,
                new JmeterGenerationResult("<plan/>", "user", List.of("user"), "all good"),
                new WorkspaceArtifacts(Path.of("workspace/auto_test.jmx"),
                        Path.of("workspace/test_data.csv")),
                ExecutionReport.success(4, ""),
                2));
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
    @DisplayName("names the valid modes when given one it does not know")
    void rejectsUnknownMode() {
        AgentCommandLineRunner runner = runner();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> runner.run("--mode=GRPC", "--source=a.har"))
                .withMessageContaining("Unknown mode 'GRPC'")
                .withMessageContaining("API")
                .withMessageContaining("SQL");
    }
}
