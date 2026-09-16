package com.ai.jmeter.agent.config;

import com.ai.jmeter.agent.adapter.ai.BudgetedCostGovernor;
import com.ai.jmeter.agent.adapter.ai.ModelRouter;
import com.ai.jmeter.agent.adapter.ai.PromptCatalog;
import com.ai.jmeter.agent.adapter.ai.SpringAiRootCauseAnalyzer;
import com.ai.jmeter.agent.adapter.ai.SpringAiAgentAdapter;
import com.ai.jmeter.agent.adapter.cli.AgentCommandLineRunner;
import com.ai.jmeter.agent.adapter.cli.JtlResultParser;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderJmeterAdapter;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderProcessRunner;
import com.ai.jmeter.agent.adapter.cli.ProcessRunner;
import com.ai.jmeter.agent.adapter.fs.FileSystemWorkspaceAdapter;
import com.ai.jmeter.agent.adapter.jmx.DomJmxDocumentAdapter;
import com.ai.jmeter.agent.adapter.k8s.KubernetesJmeterAdapter;
import com.ai.jmeter.agent.adapter.k8s.KubernetesSettings;
import com.ai.jmeter.agent.adapter.memory.JsonlHealMemoryAdapter;
import com.ai.jmeter.agent.adapter.results.JsonlResultStore;
import com.ai.jmeter.agent.adapter.parser.HarParserAdapter;
import com.ai.jmeter.agent.adapter.parser.OpenApiParserAdapter;
import com.ai.jmeter.agent.adapter.parser.PostmanCollectionParserAdapter;
import com.ai.jmeter.agent.adapter.parser.SqlLogParserAdapter;
import com.ai.jmeter.agent.adapter.parser.StreamingManifestParserAdapter;
import com.ai.jmeter.agent.adapter.redaction.PatternSensitiveDataRedactor;
import com.ai.jmeter.agent.adapter.virtualization.WireMockVirtualizationAdapter;
import com.ai.jmeter.agent.adapter.workload.AccessLogWorkloadProfiler;
import com.ai.jmeter.agent.domain.analysis.RegressionAnalyzer;
import com.ai.jmeter.agent.orchestrator.OrchestratorSettings;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
import com.ai.jmeter.agent.orchestrator.TrafficParserRegistry;
import com.ai.jmeter.agent.port.CostGovernorPort;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.HealMemoryPort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.JmxDocumentPort;
import com.ai.jmeter.agent.port.ResultStorePort;
import com.ai.jmeter.agent.port.RootCauseAnalyzerPort;
import com.ai.jmeter.agent.port.SensitiveDataRedactorPort;
import com.ai.jmeter.agent.port.ServiceVirtualizationPort;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.WorkloadProfilerPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

/**
 * The composition root: the one place where ports are bound to adapters.
 *
 * <p>Wiring is explicit rather than annotation-driven so that the domain, ports and orchestrator
 * stay free of framework types. Everything Spring knows about this application it learns here,
 * which keeps the hexagon's inside genuinely independent of its outside — and makes the core
 * constructible in a unit test with nothing but {@code new}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AgentProperties.class)
public class AgentConfiguration {

    @Bean
    public ChatClient jmeterChatClient(ChatClient.Builder chatClientBuilder) {
        return chatClientBuilder.build();
    }

    @Bean
    public PromptCatalog promptCatalog(
            @Value("classpath:/prompts/api-jmeter-system.st") Resource apiPrompt,
            @Value("classpath:/prompts/sql-jmeter-system.st") Resource sqlPrompt,
            @Value("classpath:/prompts/streaming-jmeter-system.st") Resource streamingPrompt,
            @Value("classpath:/prompts/heal-script.st") Resource healPrompt,
            @Value("classpath:/prompts/repair-plan.st") Resource repairPrompt,
            @Value("classpath:/prompts/root-cause.st") Resource rootCausePrompt) {
        return new PromptCatalog(apiPrompt, sqlPrompt, streamingPrompt,
                healPrompt, repairPrompt, rootCausePrompt);
    }

    @Bean
    public JmxDocumentPort jmxDocumentPort() {
        return new DomJmxDocumentAdapter();
    }

    @Bean
    public CostGovernorPort costGovernorPort(AgentProperties properties) {
        return new BudgetedCostGovernor(properties.tokenBudget());
    }

    @Bean
    public ModelRouter modelRouter(AgentProperties properties) {
        return new ModelRouter(properties.modelsByTurn());
    }

    @Bean
    public JmeterAgentPort jmeterAgentPort(
            ChatClient jmeterChatClient,
            PromptCatalog promptCatalog,
            CostGovernorPort costGovernorPort,
            ModelRouter modelRouter) {
        return new SpringAiAgentAdapter(
                jmeterChatClient, promptCatalog, costGovernorPort, modelRouter);
    }

    @Bean
    public TrafficParserPort harParserAdapter(ObjectMapper objectMapper, AgentProperties properties) {
        return new HarParserAdapter(
                objectMapper, properties.maxHarEntries(), properties.maxHarBodyCharacters());
    }

    @Bean
    public TrafficParserPort sqlLogParserAdapter(AgentProperties properties) {
        return new SqlLogParserAdapter(properties.maxSqlQueries());
    }

    @Bean
    public TrafficParserPort openApiParserAdapter(
            ObjectMapper objectMapper, AgentProperties properties) {
        return new OpenApiParserAdapter(objectMapper, properties.maxHarEntries());
    }

    @Bean
    public TrafficParserPort postmanCollectionParserAdapter(
            ObjectMapper objectMapper, AgentProperties properties) {
        return new PostmanCollectionParserAdapter(
                objectMapper, properties.maxHarEntries(), properties.maxHarBodyCharacters());
    }

    @Bean
    public TrafficParserPort streamingManifestParserAdapter(
            ObjectMapper objectMapper, AgentProperties properties) {
        return new StreamingManifestParserAdapter(objectMapper, properties.maxTopics());
    }

    @Bean
    public TrafficParserRegistry trafficParserRegistry(List<TrafficParserPort> parsers) {
        return new TrafficParserRegistry(parsers);
    }

    @Bean
    public ProcessRunner processRunner() {
        return new ProcessBuilderProcessRunner();
    }

    @Bean
    public JtlResultParser jtlResultParser(AgentProperties properties) {
        return new JtlResultParser(properties.maxRecordedFailures());
    }

    /**
     * Binds the execution port to whichever engine the operator configured.
     *
     * <p>The two engines are interchangeable precisely because the agentic loop only ever asks
     * the port to run a plan and report a verdict; neither it nor the domain knows whether that
     * happened in one local JVM or across a fleet.
     */
    @Bean
    public ExecutionEnginePort executionEnginePort(
            ProcessRunner processRunner, JtlResultParser jtlResultParser, AgentProperties properties) {
        if (properties.distributed()) {
            return new KubernetesJmeterAdapter(
                    processRunner,
                    jtlResultParser,
                    new KubernetesSettings(
                            properties.kubectlPath(),
                            properties.kubernetesNamespace(),
                            properties.jmeterImage(),
                            properties.workers(),
                            properties.distributedResultsPath(),
                            properties.executionTimeout()),
                    properties.workspace());
        }
        return new ProcessBuilderJmeterAdapter(
                processRunner,
                jtlResultParser,
                properties.homePath(),
                properties.workspace(),
                properties.executionTimeout(),
                properties.maxProcessOutputCharacters());
    }

    @Bean
    public WorkspacePort workspacePort(AgentProperties properties) {
        return new FileSystemWorkspaceAdapter(properties.workspace(), properties.resolvedLibPath());
    }

    @Bean
    public AgentCommandLineRunner agentCommandLineRunner(SelfHealingOrchestrator orchestrator) {
        return new AgentCommandLineRunner(orchestrator);
    }

    @Bean
    public HealMemoryPort healMemoryPort(ObjectMapper objectMapper, AgentProperties properties) {
        return new JsonlHealMemoryAdapter(
                objectMapper, properties.workspace().resolve("heal-memory.jsonl"));
    }

    @Bean
    public RootCauseAnalyzerPort rootCauseAnalyzerPort(
            ChatClient jmeterChatClient,
            PromptCatalog promptCatalog,
            CostGovernorPort costGovernorPort) {
        return new SpringAiRootCauseAnalyzer(jmeterChatClient, promptCatalog, costGovernorPort);
    }

    @Bean
    public ResultStorePort resultStorePort(ObjectMapper objectMapper, AgentProperties properties) {
        return new JsonlResultStore(
                objectMapper, properties.workspace().resolve("run-history.jsonl"));
    }

    @Bean
    public RegressionAnalyzer regressionAnalyzer(AgentProperties properties) {
        return new RegressionAnalyzer(
                properties.minimumBaselineRuns(),
                properties.regressionDeviationThreshold(),
                properties.regressionMinimumChangeRatio());
    }

    @Bean
    public ServiceVirtualizationPort serviceVirtualizationPort(
            ObjectMapper objectMapper, AgentProperties properties) {
        return new WireMockVirtualizationAdapter(
                objectMapper, properties.workspace(),
                properties.stubImage(), properties.stubPort());
    }

    @Bean
    public WorkloadProfilerPort workloadProfilerPort(AgentProperties properties) {
        return new AccessLogWorkloadProfiler(properties.workloadRampUpSeconds());
    }

    @Bean
    public SensitiveDataRedactorPort sensitiveDataRedactorPort() {
        return new PatternSensitiveDataRedactor();
    }

    @Bean
    public SelfHealingOrchestrator selfHealingOrchestrator(
            TrafficParserRegistry trafficParserRegistry,
            JmeterAgentPort jmeterAgentPort,
            ExecutionEnginePort executionEnginePort,
            WorkspacePort workspacePort,
            SensitiveDataRedactorPort sensitiveDataRedactorPort,
            JmxDocumentPort jmxDocumentPort,
            HealMemoryPort healMemoryPort,
            CostGovernorPort costGovernorPort,
            WorkloadProfilerPort workloadProfilerPort,
            ResultStorePort resultStorePort,
            RegressionAnalyzer regressionAnalyzer,
            RootCauseAnalyzerPort rootCauseAnalyzerPort,
            AgentProperties properties) {
        return new SelfHealingOrchestrator(
                trafficParserRegistry,
                jmeterAgentPort,
                executionEnginePort,
                workspacePort,
                sensitiveDataRedactorPort,
                jmxDocumentPort,
                healMemoryPort,
                costGovernorPort,
                workloadProfilerPort,
                resultStorePort,
                regressionAnalyzer,
                rootCauseAnalyzerPort,
                new OrchestratorSettings(
                        properties.maxRetries(),
                        properties.strictCompliance(),
                        properties.recalledPrecedents(),
                        properties.historyDepth(),
                        properties.traceCorrelation()));
    }
}
