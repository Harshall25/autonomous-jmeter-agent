package com.ai.jmeter.agent.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.adapter.ai.PromptCatalog;
import com.ai.jmeter.agent.adapter.ai.SpringAiAgentAdapter;
import com.ai.jmeter.agent.adapter.api.ControlPlaneController;
import com.ai.jmeter.agent.adapter.ci.GitHubActionsBuildReporter;
import com.ai.jmeter.agent.adapter.cli.AgentCommandLineRunner;
import com.ai.jmeter.agent.adapter.cli.JtlResultParser;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderJmeterAdapter;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderProcessRunner;
import com.ai.jmeter.agent.adapter.cli.ProcessRunner;
import com.ai.jmeter.agent.adapter.fs.FileSystemWorkspaceAdapter;
import com.ai.jmeter.agent.adapter.k8s.KubernetesJmeterAdapter;
import com.ai.jmeter.agent.adapter.ledger.JsonlRunLedger;
import com.ai.jmeter.agent.adapter.virtualization.WireMockVirtualizationAdapter;
import com.ai.jmeter.agent.adapter.parser.HarParserAdapter;
import com.ai.jmeter.agent.adapter.parser.SqlLogParserAdapter;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ci.GatePolicy;
import com.ai.jmeter.agent.domain.ci.GateVerdict;
import com.ai.jmeter.agent.domain.ci.PerformanceGateFailedException;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
import com.ai.jmeter.agent.orchestrator.TrafficParserRegistry;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.RunLedgerPort;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import com.ai.jmeter.agent.support.TestFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.ExitCodeExceptionMapper;
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
                new ClassPathResource("prompts/repair-plan.st"),
                new ClassPathResource("prompts/root-cause.st"));
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
    @DisplayName("binds the execution port to the Kubernetes adapter when distributed")
    void bindsDistributedExecutionPort() {
        // The loop is unchanged either way: it only ever asks the port to run a plan and report
        // a verdict, so swapping engines is an adapter change rather than a rewrite.
        AgentProperties distributed = new AgentProperties(
                java.nio.file.Path.of("/opt/jmeter/bin"), 3,
                java.nio.file.Path.of("workspace"), java.time.Duration.ofMinutes(10), null,
                150, 2000, 200, 8000, 500, false, 3, 50, 30, 10, 5, 3.0, 1.10, false,
                true, "kubectl", "perf", "jmeter:5.6", 4,
                java.nio.file.Path.of("workspace/shards"), "wiremock:3", 8089, 0L,
                java.util.Map.of());

        ExecutionEnginePort port = configuration.executionEnginePort(
                configuration.processRunner(),
                configuration.jtlResultParser(distributed),
                distributed);

        assertThat(port).isInstanceOf(KubernetesJmeterAdapter.class);
    }

    @Test
    @DisplayName("binds the virtualization port to the WireMock adapter")
    void bindsVirtualizationPort() {
        assertThat(configuration.serviceVirtualizationPort(new ObjectMapper(), properties()))
                .isInstanceOf(WireMockVirtualizationAdapter.class);
    }

    @Test
    @DisplayName("binds the run ledger the control plane reads from")
    void bindsRunLedgerPort() {
        assertThat(configuration.runLedgerPort(new ObjectMapper(), properties()))
                .isInstanceOf(JsonlRunLedger.class);
    }

    @Test
    @DisplayName("serves the control plane over the ledger it bound")
    void bindsControlPlaneController() {
        assertThat(configuration.controlPlaneController(mock(RunLedgerPort.class)))
                .isInstanceOf(ControlPlaneController.class);
    }

    @Test
    @DisplayName("binds the build reporter to the GitHub Actions adapter")
    void bindsBuildReporterPort() {
        assertThat(configuration.buildReporterPort(TestFixtures.gateProperties()))
                .isInstanceOf(GitHubActionsBuildReporter.class);
    }

    @Test
    @DisplayName("builds the gate from the pipeline's own policy")
    void bindsPerformanceGate() {
        assertThat(configuration.performanceGate(TestFixtures.gateProperties())).isNotNull();
        assertThat(TestFixtures.gateProperties().toPolicy())
                .isEqualTo(new GatePolicy(true, false, 0, 0));
    }

    @Test
    @DisplayName("gives a blocked merge an exit code a pipeline can tell from a crash")
    void mapsGateFailureToItsOwnExitCode() {
        ExitCodeExceptionMapper mapper = configuration.performanceGateExitCodeMapper();

        assertThat(mapper.getExitCode(new PerformanceGateFailedException(
                GateVerdict.blocked(List.of("checkout regressed"))))).isEqualTo(2);
        assertThat(mapper.getExitCode(new IllegalStateException("something broke"))).isEqualTo(1);
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
                configuration.runLedgerPort(new ObjectMapper(), properties()),
                configuration.regressionAnalyzer(properties()),
                configuration.rootCauseAnalyzerPort(
                        mock(ChatClient.class), promptCatalog(),
                        configuration.costGovernorPort(properties())),
                properties());

        assertThat(orchestrator).isNotNull();
        GateProperties gate = TestFixtures.gateProperties();
        assertThat(configuration.agentCommandLineRunner(
                orchestrator,
                configuration.performanceGate(gate),
                configuration.buildReporterPort(gate),
                gate))
                .isInstanceOf(AgentCommandLineRunner.class);
    }
}
