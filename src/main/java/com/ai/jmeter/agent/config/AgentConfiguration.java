package com.ai.jmeter.agent.config;

import com.ai.jmeter.agent.adapter.ai.BudgetedCostGovernor;
import com.ai.jmeter.agent.adapter.ai.ModelRouter;
import com.ai.jmeter.agent.adapter.ai.PromptCatalog;
import com.ai.jmeter.agent.adapter.ai.SpringAiRootCauseAnalyzer;
import com.ai.jmeter.agent.adapter.ai.SpringAiAgentAdapter;
import com.ai.jmeter.agent.adapter.api.ControlPlaneController;
import com.ai.jmeter.agent.adapter.ci.GitHubActionsBuildReporter;
import com.ai.jmeter.agent.adapter.cli.AgentCommandLineRunner;
import com.ai.jmeter.agent.adapter.cli.EvaluationCommandLineRunner;
import com.ai.jmeter.agent.adapter.cli.JtlResultParser;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderJmeterAdapter;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderProcessRunner;
import com.ai.jmeter.agent.adapter.cli.ProcessRunner;
import com.ai.jmeter.agent.adapter.eval.YamlEvaluationCorpus;
import com.ai.jmeter.agent.adapter.fs.FileSystemWorkspaceAdapter;
import com.ai.jmeter.agent.adapter.jmx.DomJmxDocumentAdapter;
import com.ai.jmeter.agent.adapter.k8s.KubernetesJmeterAdapter;
import com.ai.jmeter.agent.adapter.k8s.KubernetesSettings;
import com.ai.jmeter.agent.adapter.ledger.JsonlRunLedger;
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
import com.ai.jmeter.agent.domain.ci.PerformanceGate;
import com.ai.jmeter.agent.domain.ci.PerformanceGateFailedException;
import com.ai.jmeter.agent.orchestrator.EvaluationHarness;
import com.ai.jmeter.agent.orchestrator.OrchestratorSettings;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
import com.ai.jmeter.agent.orchestrator.TrafficParserRegistry;
import com.ai.jmeter.agent.port.BuildReporterPort;
import com.ai.jmeter.agent.port.CostGovernorPort;
import com.ai.jmeter.agent.port.EvaluationCorpusPort;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.HealMemoryPort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.JmxDocumentPort;
import com.ai.jmeter.agent.port.ResultStorePort;
import com.ai.jmeter.agent.port.RootCauseAnalyzerPort;
import com.ai.jmeter.agent.port.RunLedgerPort;
import com.ai.jmeter.agent.port.SensitiveDataRedactorPort;
import com.ai.jmeter.agent.port.ServiceVirtualizationPort;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.WorkloadProfilerPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.List;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ExitCodeExceptionMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
@EnableConfigurationProperties({AgentProperties.class, GateProperties.class})
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
    public AgentCommandLineRunner agentCommandLineRunner(
            SelfHealingOrchestrator orchestrator,
            PerformanceGate performanceGate,
            BuildReporterPort buildReporterPort,
            GateProperties gateProperties) {
        return new AgentCommandLineRunner(
                orchestrator, performanceGate, buildReporterPort, gateProperties.enabled());
    }

    /**
     * Scores the agent against a golden corpus.
     *
     * <p>Wired into the application rather than kept in the test tree because the corpus belongs
     * to whoever runs the agent: a team scores it against their own captures, on their own
     * schedule, using the same binary they deploy.
     */
    @Bean
    public EvaluationHarness evaluationHarness(
            SelfHealingOrchestrator orchestrator, JmxDocumentPort jmxDocumentPort) {
        return new EvaluationHarness(orchestrator, jmxDocumentPort);
    }

    @Bean
    public EvaluationCorpusPort evaluationCorpusPort() {
        return new YamlEvaluationCorpus(new ObjectMapper(new YAMLFactory()));
    }

    @Bean
    public EvaluationCommandLineRunner evaluationCommandLineRunner(
            EvaluationCorpusPort evaluationCorpusPort, EvaluationHarness evaluationHarness) {
        return new EvaluationCommandLineRunner(evaluationCorpusPort, evaluationHarness);
    }

    @Bean
    public PerformanceGate performanceGate(GateProperties gateProperties) {
        return new PerformanceGate(gateProperties.toPolicy());
    }

    @Bean
    public BuildReporterPort buildReporterPort(GateProperties gateProperties) {
        return new GitHubActionsBuildReporter(
                gateProperties.reportFile(), gateProperties.stepSummaryFile(), System.out);
    }

    /**
     * Gives a blocked merge its own exit code.
     *
     * <p>A pipeline has to be able to tell "the change is too slow" from "the agent crashed":
     * the first is a finding to act on, the second is a bug to report, and collapsing both into
     * exit 1 is how a gate ends up quietly bypassed.
     */
    @Bean
    public ExitCodeExceptionMapper performanceGateExitCodeMapper() {
        return exception -> exception instanceof PerformanceGateFailedException blocked
                ? blocked.verdict().exitCode()
                : 1;
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
    public RunLedgerPort runLedgerPort(ObjectMapper objectMapper, AgentProperties properties) {
        return new JsonlRunLedger(
                objectMapper, properties.workspace().resolve("run-ledger.jsonl"));
    }

    /**
     * Serves the run ledger only when the application was started as a web application.
     *
     * <p>A CLI run started from a pipeline has no business opening a port, and an agent that
     * silently binds one in every CI container would be an unwelcome surprise. The control plane
     * is something an operator asks for with
     * {@code --spring.main.web-application-type=servlet}.
     */
    @Bean
    @ConditionalOnWebApplication
    public ControlPlaneController controlPlaneController(RunLedgerPort runLedgerPort) {
        return new ControlPlaneController(runLedgerPort);
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
            RunLedgerPort runLedgerPort,
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
                runLedgerPort,
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
