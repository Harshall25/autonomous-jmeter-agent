package com.ai.jmeter.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.adapter.ai.PromptCatalog;
import com.ai.jmeter.agent.adapter.ai.SpringAiAgentAdapter;
import com.ai.jmeter.agent.adapter.cli.AgentCommandLineRunner;
import com.ai.jmeter.agent.adapter.cli.JtlResultParser;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderJmeterAdapter;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderProcessRunner;
import com.ai.jmeter.agent.adapter.cli.ProcessRunner;
import com.ai.jmeter.agent.adapter.fs.FileSystemWorkspaceAdapter;
import com.ai.jmeter.agent.adapter.parser.HarParserAdapter;
import com.ai.jmeter.agent.adapter.parser.SqlLogParserAdapter;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
import com.ai.jmeter.agent.orchestrator.TrafficParserRegistry;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import com.ai.jmeter.agent.support.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

/**
 * Verifies the composition root binds every port to its intended adapter.
 *
 * <p>Wiring is the one thing a hexagonal core cannot check for itself: the domain compiles
 * happily against ports that were never bound.
 */
@DisplayName("AgentConfiguration")
class AgentConfigurationTest {

    private final AgentConfiguration configuration = new AgentConfiguration();

    private static AgentProperties properties() {
        return TestFixtures.properties();
    }

    private PromptCatalog promptCatalog() {
        return configuration.promptCatalog(
                new ClassPathResource("prompts/api-jmeter-system.st"),
                new ClassPathResource("prompts/sql-jmeter-system.st"),
                new ClassPathResource("prompts/streaming-jmeter-system.st"),
                new ClassPathResource("prompts/heal-script.st"),
                new ClassPathResource("prompts/repair-plan.st"));
    }

    @Test
    @DisplayName("builds a ChatClient from the auto-configured builder")
    void buildsChatClient() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);

        assertThat(configuration.jmeterChatClient(builder)).isSameAs(chatClient);
    }

    @Test
    @DisplayName("loads all three prompt templates")
    void loadsPromptCatalog() {
        PromptCatalog catalog = promptCatalog();

        assertThat(catalog.systemPromptFor(ExecutionMode.API)).contains("Auto-Correlate");
        assertThat(catalog.systemPromptFor(ExecutionMode.SQL)).contains("JDBC");
        assertThat(catalog.healSystemPrompt("<plan/>", "401")).contains("<plan/>");
    }

    @Test
    @DisplayName("binds the reasoning port to the Spring AI adapter")
    void bindsAgentPort() {
        JmeterAgentPort port = configuration.jmeterAgentPort(
                mock(ChatClient.class),
                promptCatalog(),
                configuration.costGovernorPort(properties()),
                configuration.modelRouter(properties()));

        assertThat(port).isInstanceOf(SpringAiAgentAdapter.class);
    }

    @Test
    @DisplayName("binds each parser port to the adapter for its mode")
    void bindsParserPorts() {
        TrafficParserPort har =
                configuration.harParserAdapter(new ObjectMapper(), properties());
        TrafficParserPort sql = configuration.sqlLogParserAdapter(properties());

        assertThat(har).isInstanceOf(HarParserAdapter.class);
        assertThat(har.supportedMode()).isEqualTo(ExecutionMode.API);
        assertThat(sql).isInstanceOf(SqlLogParserAdapter.class);
        assertThat(sql.supportedMode()).isEqualTo(ExecutionMode.SQL);
    }

    @Test
    @DisplayName("registers every parser so both modes route correctly")
    void buildsParserRegistry() {
        TrafficParserPort har = configuration.harParserAdapter(new ObjectMapper(), properties());
        TrafficParserPort sql = configuration.sqlLogParserAdapter(properties());

        TrafficParserRegistry registry = configuration.trafficParserRegistry(List.of(har, sql));

        assertThat(registry.parserFor(ExecutionMode.API)).isSameAs(har);
        assertThat(registry.parserFor(ExecutionMode.SQL)).isSameAs(sql);
    }

    @Test
    @DisplayName("binds the execution port to the ProcessBuilder adapter")
    void bindsExecutionPort() {
        ProcessRunner runner = configuration.processRunner();
        JtlResultParser jtlParser = configuration.jtlResultParser(properties());

        ExecutionEnginePort port =
                configuration.executionEnginePort(runner, jtlParser, properties());

        assertThat(runner).isInstanceOf(ProcessBuilderProcessRunner.class);
        assertThat(jtlParser).isNotNull();
        assertThat(port).isInstanceOf(ProcessBuilderJmeterAdapter.class);
    }

    @Test
    @DisplayName("binds the workspace port to the filesystem adapter")
    void bindsWorkspacePort() {
        WorkspacePort port = configuration.workspacePort(properties());

        assertThat(port).isInstanceOf(FileSystemWorkspaceAdapter.class);
    }

    @Test
    @DisplayName("assembles the orchestrator with the configured retry budget")
    void buildsOrchestrator() {
        SelfHealingOrchestrator orchestrator = configuration.selfHealingOrchestrator(
                configuration.trafficParserRegistry(
                        List.of(configuration.sqlLogParserAdapter(properties()))),
                mock(JmeterAgentPort.class),
                mock(ExecutionEnginePort.class),
                mock(WorkspacePort.class),
                configuration.sensitiveDataRedactorPort(),
                configuration.jmxDocumentPort(),
                configuration.healMemoryPort(new ObjectMapper(), properties()),
                configuration.costGovernorPort(properties()),
                configuration.workloadProfilerPort(properties()),
                configuration.resultStorePort(new ObjectMapper(), properties()),
                configuration.regressionAnalyzer(properties()),
                properties());

        assertThat(orchestrator).isNotNull();
        assertThat(configuration.agentCommandLineRunner(orchestrator))
                .isInstanceOf(AgentCommandLineRunner.class);
    }
}
