package com.ai.jmeter.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.adapter.ai.RepairPlanResponse;
import com.ai.jmeter.agent.adapter.ai.SpringAiAgentAdapter;
import com.ai.jmeter.agent.adapter.jmx.DomJmxDocumentAdapter;
import com.ai.jmeter.agent.adapter.redaction.PatternSensitiveDataRedactor;
import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.SampleFailure;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import com.ai.jmeter.agent.support.TestFixtures;
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
import org.springframework.ai.chat.client.ChatClient;

/**
 * Drives the loop end to end through the real AI, redaction and JMX adapters, with only the
 * {@link ChatClient} and the JMeter process mocked.
 *
 * <p>Where {@code SelfHealingOrchestratorTest} verifies the loop against mocked ports, this one
 * verifies the properties that only emerge when the real adapters are wired together: how many
 * times the loop reaches the model, that a repair turn edits the plan rather than replacing it,
 * and that a live credential in the capture never reaches the prompt.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Self-healing loop, model interaction")
class SelfHealingLoopModelInteractionTest {

    private static final Path SOURCE = Path.of("capture.har");

    /** Carries a live bearer token, so redaction has something real to strip. */
    private static final String CAPTURED_TRAFFIC = """
            [{"method":"GET","url":"https://api.shop.test/v1/orders",
              "headers":{"Authorization":"Bearer eyJhbGciOiJIUzI1NiJ9.cGF5bG9hZA.c2lnbmF0dXJl"}}]""";

    private static final ExecutionReport UNAUTHORIZED = ExecutionReport.sampleFailures(
            1, List.of(new SampleFailure("orders", "401", "Unauthorized", "Token empty")), "");

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    @Mock
    private TrafficParserPort harParser;

    @Mock
    private ExecutionEnginePort executionEngine;

    @Mock
    private WorkspacePort workspace;

    private SelfHealingOrchestrator orchestrator() {
        when(harParser.supportedMode()).thenReturn(ExecutionMode.API);
        when(harParser.parse(SOURCE)).thenReturn(CAPTURED_TRAFFIC);
        when(workspace.write(any())).thenReturn(new WorkspaceArtifacts(
                Path.of("workspace/auto_test.jmx"), Path.of("workspace/test_data.csv")));

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);

        when(responseSpec.entity(JmeterGenerationResult.class)).thenReturn(
                new JmeterGenerationResult(TestFixtures.VALID_JMX, "user\nalice",
                        List.of("user"), "initial plan"));
        when(responseSpec.entity(RepairPlanResponse.class)).thenReturn(
                new RepairPlanResponse(
                        "The login response was never mined for the bearer token",
                        false,
                        List.of(new RepairPlanResponse.MutationCommand(
                                "jsonPathExtractor", "login", "auth_token", "$.access_token",
                                null, null, null, null, null, null, null, null,
                                null, null, null, null, null))));

        return new SelfHealingOrchestrator(
                new TrafficParserRegistry(List.of(harParser)),
                new SpringAiAgentAdapter(chatClient, TestFixtures.promptCatalog()),
                executionEngine,
                workspace,
                new PatternSensitiveDataRedactor(),
                new DomJmxDocumentAdapter(),
                3,
                false);
    }

    @Test
    @DisplayName("reaches the model exactly twice when one repair turn is needed")
    void promptsModelTwiceWhenHealingOnce() {
        when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, ExecutionReport.success(1, ""));

        AgentRunOutcome outcome = orchestrator().run(new AgentRunRequest(ExecutionMode.API, SOURCE));

        verify(chatClient, times(2)).prompt();
        assertThat(outcome.attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("reaches the model once when the first plan passes")
    void promptsModelOnceWhenNoHealingNeeded() {
        when(executionEngine.execute(any())).thenReturn(ExecutionReport.success(1, ""));

        orchestrator().run(new AgentRunRequest(ExecutionMode.API, SOURCE));

        verify(chatClient, times(1)).prompt();
    }

    @Test
    @DisplayName("the repair turn edits the plan in place instead of replacing it")
    void repairTurnEditsThePlan() {
        when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, ExecutionReport.success(1, ""));

        AgentRunOutcome outcome = orchestrator().run(new AgentRunRequest(ExecutionMode.API, SOURCE));

        assertThat(outcome.script().jmxXmlContent())
                .as("the model's edit was applied to the existing tree")
                .contains("JSONPostProcessor")
                .contains("auth_token")
                .contains("$.access_token")
                .as("and the rest of the plan survived untouched")
                .contains("api.shop.test")
                .contains("/v1/orders");
    }

    @Test
    @DisplayName("the repair turn is briefed with structure, not with raw XML")
    void repairTurnIsBriefedWithStructure() {
        when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, ExecutionReport.success(1, ""));

        orchestrator().run(new AgentRunRequest(ExecutionMode.API, SOURCE));

        ArgumentCaptor<String> systemPrompts = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(2)).system(systemPrompts.capture());

        assertThat(systemPrompts.getAllValues().get(0)).contains("Auto-Correlate");
        assertThat(systemPrompts.getAllValues().get(1))
                .contains("Samplers: [login, orders]")
                .contains("responseCode=401")
                .contains("Token empty")
                .as("the repair brief summarizes the plan rather than pasting it")
                .doesNotContain("HTTPSamplerProxy");
    }

    @Test
    @DisplayName("no live credential from the capture ever reaches the model")
    void credentialsNeverReachTheModel() {
        when(executionEngine.execute(any())).thenReturn(ExecutionReport.success(1, ""));

        orchestrator().run(new AgentRunRequest(ExecutionMode.API, SOURCE));

        ArgumentCaptor<String> userTurns = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).user(userTurns.capture());

        assertThat(userTurns.getValue())
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .contains("${__P(agent.secret.credential.1)}");
    }
}
