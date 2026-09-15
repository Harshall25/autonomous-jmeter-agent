package com.ai.jmeter.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.adapter.ai.PromptCatalog;
import com.ai.jmeter.agent.adapter.ai.SpringAiAgentAdapter;
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
import org.springframework.core.io.ClassPathResource;

/**
 * Drives the loop end to end through the real AI adapter, with only the {@link ChatClient}
 * mocked.
 *
 * <p>Where {@code SelfHealingOrchestratorTest} verifies the loop against a mocked port, this one
 * verifies how many times the loop actually reaches the model and what it says when it does —
 * the property that matters for both cost and correctness of an autonomous agent.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("Self-healing loop, model interaction")
class SelfHealingLoopModelInteractionTest {

    private static final Path SOURCE = Path.of("capture.har");

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
        when(harParser.parse(SOURCE)).thenReturn("[{\"url\":\"/v1/orders\"}]");
        when(workspace.write(any())).thenReturn(new WorkspaceArtifacts(
                Path.of("workspace/auto_test.jmx"), Path.of("workspace/test_data.csv")));

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
        when(responseSpec.entity(JmeterGenerationResult.class))
                .thenReturn(new JmeterGenerationResult("<plan>draft</plan>", "", List.of(), "v1"))
                .thenReturn(new JmeterGenerationResult("<plan>repaired</plan>", "", List.of(), "v2"));

        SpringAiAgentAdapter agentAdapter = new SpringAiAgentAdapter(chatClient, new PromptCatalog(
                new ClassPathResource("prompts/api-jmeter-system.st"),
                new ClassPathResource("prompts/sql-jmeter-system.st"),
                new ClassPathResource("prompts/heal-script.st")));

        return new SelfHealingOrchestrator(
                new TrafficParserRegistry(List.of(harParser)),
                agentAdapter,
                executionEngine,
                workspace,
                3);
    }

    @Test
    @DisplayName("reaches the model exactly twice when one repair turn is needed")
    void promptsModelTwiceWhenHealingOnce() {
        when(executionEngine.execute(any()))
                .thenReturn(ExecutionReport.sampleFailures(
                        1,
                        List.of(new SampleFailure("orders", "401", "Unauthorized", "")),
                        ""))
                .thenReturn(ExecutionReport.success(1, ""));

        AgentRunOutcome outcome =
                orchestrator().run(new AgentRunRequest(ExecutionMode.API, SOURCE));

        verify(chatClient, times(2)).prompt();
        assertThat(outcome.attempts()).isEqualTo(2);
        assertThat(outcome.script().jmxXmlContent()).isEqualTo("<plan>repaired</plan>");
    }

    @Test
    @DisplayName("reaches the model once when the first plan passes")
    void promptsModelOnceWhenNoHealingNeeded() {
        when(executionEngine.execute(any())).thenReturn(ExecutionReport.success(1, ""));

        orchestrator().run(new AgentRunRequest(ExecutionMode.API, SOURCE));

        verify(chatClient, times(1)).prompt();
    }

    @Test
    @DisplayName("the second prompt carries the failed plan and the 401 evidence")
    void secondPromptCarriesEvidence() {
        when(executionEngine.execute(any()))
                .thenReturn(ExecutionReport.sampleFailures(
                        1,
                        List.of(new SampleFailure("orders", "401", "Unauthorized", "Token empty")),
                        ""))
                .thenReturn(ExecutionReport.success(1, ""));

        orchestrator().run(new AgentRunRequest(ExecutionMode.API, SOURCE));

        ArgumentCaptor<String> systemPrompts = ArgumentCaptor.forClass(String.class);
        verify(requestSpec, times(2)).system(systemPrompts.capture());

        assertThat(systemPrompts.getAllValues().get(0)).contains("Auto-Correlate");
        assertThat(systemPrompts.getAllValues().get(1))
                .contains("failed during execution")
                .contains("<plan>draft</plan>")
                .contains("responseCode=401")
                .contains("Token empty");
    }
}
