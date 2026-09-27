package com.ai.jmeter.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.ExecutionStatus;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.SampleFailure;
import com.ai.jmeter.agent.domain.SelfHealingFailedException;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Drives the agentic loop against mocked ports.
 *
 * <p>These are the tests that pin the agent's central promise: a plan is finished only when a
 * real run says so, a failing run always produces another repair turn, and the retry budget is
 * genuinely bounded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SelfHealingOrchestrator")
class SelfHealingOrchestratorTest {

    private static final Path SOURCE = Path.of("capture.har");
    private static final String PARSED_TRAFFIC = "[{\"url\":\"/v1/login\"}]";

    private static final JmeterGenerationResult FIRST_DRAFT =
            new JmeterGenerationResult("<plan>draft</plan>", "user\nalice", List.of("user"), "v1");
    private static final JmeterGenerationResult REPAIRED =
            new JmeterGenerationResult("<plan>repaired</plan>", "user\nalice", List.of("user"), "v2");

    private static final WorkspaceArtifacts ARTIFACTS =
            new WorkspaceArtifacts(Path.of("workspace/auto_test.jmx"), Path.of("workspace/test_data.csv"));

    private static final ExecutionReport UNAUTHORIZED = ExecutionReport.sampleFailures(
            1,
            List.of(new SampleFailure("login", "401", "Unauthorized", "Token was empty")),
            "");
    private static final ExecutionReport CLEAN_RUN = ExecutionReport.success(4, "");

    @Mock
    private TrafficParserPort harParser;

    @Mock
    private JmeterAgentPort agent;

    @Mock
    private ExecutionEnginePort executionEngine;

    @Mock
    private WorkspacePort workspace;

    @BeforeEach
    void setUp() {
        when(harParser.supportedMode()).thenReturn(ExecutionMode.API);
        when(harParser.parse(SOURCE)).thenReturn(PARSED_TRAFFIC);
        when(workspace.write(any())).thenReturn(ARTIFACTS);
        when(agent.generateScript(anyString(), any())).thenReturn(FIRST_DRAFT);
        when(agent.healScript(anyString(), anyString())).thenReturn(REPAIRED);
    }

    private SelfHealingOrchestrator orchestrator(int maxAttempts) {
        return new SelfHealingOrchestrator(
                new TrafficParserRegistry(List.of(harParser)),
                agent,
                executionEngine,
                workspace,
                maxAttempts);
    }

    private AgentRunOutcome run(int maxAttempts) {
        return orchestrator(maxAttempts).run(new AgentRunRequest(ExecutionMode.API, SOURCE));
    }

    @Test
    @DisplayName("Case 1: a plan that passes first time ends the loop immediately")
    void terminatesOnFirstSuccess() {
        when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

        AgentRunOutcome outcome = run(3);

        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.healedFirstTime()).isTrue();
        assertThat(outcome.script()).isSameAs(FIRST_DRAFT);
        assertThat(outcome.report()).isSameAs(CLEAN_RUN);
        assertThat(outcome.artifacts()).isSameAs(ARTIFACTS);
        assertThat(outcome.mode()).isEqualTo(ExecutionMode.API);

        verify(agent).generateScript(PARSED_TRAFFIC, ExecutionMode.API);
        verify(agent, never()).healScript(anyString(), anyString());
        verify(executionEngine, times(1)).execute(ARTIFACTS.jmxScript());
    }

    @Test
    @DisplayName("Case 2: a 401 triggers exactly one repair turn, and the retry passes")
    void healsThenSucceeds() {
        when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

        AgentRunOutcome outcome = run(3);

        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.healedFirstTime()).isFalse();
        assertThat(outcome.script())
                .as("the outcome carries the repaired plan, not the draft that failed")
                .isSameAs(REPAIRED);

        verify(agent, times(1)).generateScript(anyString(), any());
        verify(agent, times(1)).healScript(anyString(), anyString());
        verify(executionEngine, times(2)).execute(any());
        verify(workspace, times(2)).write(any());
    }

    @Test
    @DisplayName("Case 2: the repair turn receives the failed plan and the run evidence")
    void healReceivesFailedPlanAndEvidence() {
        when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

        run(3);

        ArgumentCaptor<String> script = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
        verify(agent).healScript(script.capture(), evidence.capture());

        assertThat(script.getValue()).isEqualTo("<plan>draft</plan>");
        assertThat(evidence.getValue())
                .contains("SAMPLE_FAILURE")
                .contains("sampler='login'")
                .contains("responseCode=401")
                .contains("Token was empty");
    }

    @Test
    @DisplayName("Case 2: each repair turn is written to the workspace before it runs")
    void writesRepairedPlanBeforeRerunning() {
        when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

        run(3);

        ArgumentCaptor<JmeterGenerationResult> written =
                ArgumentCaptor.forClass(JmeterGenerationResult.class);
        verify(workspace, times(2)).write(written.capture());

        assertThat(written.getAllValues()).containsExactly(FIRST_DRAFT, REPAIRED);
    }

    @Test
    @DisplayName("Case 3: the retry budget is bounded and exhausting it is an error")
    void failsAfterMaxAttempts() {
        when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED);

        assertThatExceptionOfType(SelfHealingFailedException.class)
                .isThrownBy(() -> run(3))
                .satisfies(thrown -> {
                    assertThat(thrown.attempts()).isEqualTo(3);
                    assertThat(thrown.lastReport()).isSameAs(UNAUTHORIZED);
                    assertThat(thrown).hasMessageContaining("after 3 attempt(s)");
                });

        verify(executionEngine, times(3)).execute(any());
        verify(agent, times(1)).generateScript(anyString(), any());
        verify(agent, times(2)).healScript(anyString(), anyString());
    }

    @Test
    @DisplayName("Case 3: a budget of one means no repair turn at all")
    void singleAttemptBudgetNeverHeals() {
        when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED);

        assertThatExceptionOfType(SelfHealingFailedException.class).isThrownBy(() -> run(1));

        verify(executionEngine, times(1)).execute(any());
        verify(agent, never()).healScript(anyString(), anyString());
    }

    @Test
    @DisplayName("keeps healing through a structurally broken plan, not just failing samples")
    void healsProcessFailuresToo() {
        when(executionEngine.execute(any()))
                .thenReturn(ExecutionReport.processFailure("Invalid XML at line 4"))
                .thenReturn(ExecutionReport.noSamples("nothing ran"))
                .thenReturn(CLEAN_RUN);

        AgentRunOutcome outcome = run(5);

        assertThat(outcome.attempts()).isEqualTo(3);
        verify(agent, times(2)).healScript(anyString(), anyString());
    }

    @Test
    @DisplayName("reads the capture through the parser registered for the requested mode")
    void parsesThroughTheRegisteredParser() {
        when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

        run(3);

        verify(harParser).parse(SOURCE);
        verify(agent).generateScript(PARSED_TRAFFIC, ExecutionMode.API);
    }

    @Test
    @DisplayName("rejects a retry budget that would let no run happen at all")
    void rejectsNonPositiveBudget() {
        TrafficParserRegistry registry = new TrafficParserRegistry(List.of(harParser));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new SelfHealingOrchestrator(
                        registry, agent, executionEngine, workspace, 0))
                .withMessageContaining("maxAttempts must be at least 1");
    }

    @Test
    @DisplayName("never asks about JDBC drivers in API mode")
    void skipsDriverCheckInApiMode() {
        when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

        run(3);

        verify(workspace, never()).jdbcDriverAvailable();
    }

    @Test
    @DisplayName("checks for a JDBC driver before spending the budget on SQL mode")
    void checksDriverInSqlMode() {
        TrafficParserPort sqlParser = org.mockito.Mockito.mock(TrafficParserPort.class);
        when(sqlParser.supportedMode()).thenReturn(ExecutionMode.SQL);
        when(sqlParser.parse(SOURCE)).thenReturn("SELECT 1;");
        when(workspace.jdbcDriverAvailable()).thenReturn(false);
        when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

        new SelfHealingOrchestrator(
                new TrafficParserRegistry(List.of(sqlParser)), agent, executionEngine, workspace, 3)
                .run(new AgentRunRequest(ExecutionMode.SQL, SOURCE));

        verify(workspace).jdbcDriverAvailable();
        verify(agent).generateScript("SELECT 1;", ExecutionMode.SQL);
    }

    @Test
    @DisplayName("proceeds quietly when the JDBC driver is present")
    void proceedsWhenDriverPresent() {
        TrafficParserPort sqlParser = org.mockito.Mockito.mock(TrafficParserPort.class);
        when(sqlParser.supportedMode()).thenReturn(ExecutionMode.SQL);
        when(sqlParser.parse(SOURCE)).thenReturn("SELECT 1;");
        when(workspace.jdbcDriverAvailable()).thenReturn(true);
        when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

        AgentRunOutcome outcome = new SelfHealingOrchestrator(
                new TrafficParserRegistry(List.of(sqlParser)), agent, executionEngine, workspace, 3)
                .run(new AgentRunRequest(ExecutionMode.SQL, SOURCE));

        assertThat(outcome.report().status()).isEqualTo(ExecutionStatus.SUCCESS);
    }

    @Test
    @DisplayName("lets a parsing failure surface instead of prompting the model with nothing")
    void parsingFailureAbortsBeforeAnyModelCall() {
        when(harParser.parse(SOURCE)).thenThrow(new IllegalStateException("corrupt capture"));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> run(3))
                .withMessage("corrupt capture");

        verifyNoInteractions(agent, executionEngine);
    }

    @Test
    @DisplayName("runs whatever plan the workspace actually wrote")
    void executesTheWrittenArtifact() {
        WorkspaceArtifacts elsewhere =
                new WorkspaceArtifacts(Path.of("/tmp/other.jmx"), Path.of("/tmp/other.csv"));
        when(workspace.write(any())).thenReturn(elsewhere);
        when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

        run(3);

        verify(executionEngine).execute(eq(Path.of("/tmp/other.jmx")));
    }
}
