package com.ai.jmeter.agent.config;

import com.ai.jmeter.agent.adapter.ai.BudgetedCostGovernor;
import com.ai.jmeter.agent.adapter.ai.ModelRouter;
import com.ai.jmeter.agent.adapter.ai.PromptCatalog;
import com.ai.jmeter.agent.adapter.ai.SpringAiRootCauseAnalyzer;
import com.ai.jmeter.agent.adapter.ai.SpringAiAgentAdapter;
import com.ai.jmeter.agent.adapter.api.ControlPlaneController;
import com.ai.jmeter.agent.adapter.api.LocalOperatorPrincipalResolver;
import com.ai.jmeter.agent.adapter.api.PrincipalResolver;
import com.ai.jmeter.agent.adapter.api.TrustedHeaderPrincipalResolver;
import com.ai.jmeter.agent.adapter.ci.GitHubActionsBuildReporter;
import com.ai.jmeter.agent.adapter.cli.AgentCommandLineRunner;
import com.ai.jmeter.agent.adapter.cli.EvaluationCommandLineRunner;
import com.ai.jmeter.agent.adapter.cli.JtlResultParser;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderJmeterAdapter;
import com.ai.jmeter.agent.adapter.cli.ProcessBuilderProcessRunner;
import com.ai.jmeter.agent.adapter.cli.ProcessRunner;
import com.ai.jmeter.agent.adapter.eval.YamlEvaluationCorpus;
import com.ai.jmeter.agent.adapter.fs.FileSystemWorkspaceAdapter;
import com.ai.jmeter.agent.adapter.governance.HmacManifestSigner;
import com.ai.jmeter.agent.adapter.governance.JsonlProvenanceStore;
import com.ai.jmeter.agent.adapter.governance.UnconfiguredManifestSigner;
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
import com.ai.jmeter.agent.domain.governance.Principal;
import com.ai.jmeter.agent.domain.governance.Role;
import com.ai.jmeter.agent.domain.governance.TenantId;
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
import com.ai.jmeter.agent.port.ManifestSignerPort;
import com.ai.jmeter.agent.port.ProvenanceStorePort;
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
import java.util.Map;
import java.util.Set;
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
@EnableConfigurationProperties({
        AgentProperties.class, GateProperties.class, TenancyProperties.class})
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

    /**
     * Resolves the workspace, under the tenant's own subdirectory when tenancy is on.
     *
     * <p>Each tenant's runs execute in their own process, so isolation is a path rather than a
     * filter: a capture written for one tenant is never in a directory another tenant's run can
     * reach. {@code TenantId} validates the id as a slug, which is what makes resolving a path
     * from it safe.
     */
    @Bean
    public WorkspacePort workspacePort(AgentProperties properties, TenancyProperties tenancy) {
        return new FileSystemWorkspaceAdapter(
                tenantWorkspace(properties, tenancy), properties.resolvedLibPath());
    }

    static java.nio.file.Path tenantWorkspace(
            AgentProperties properties, TenancyProperties tenancy) {
        return tenancy.enabled()
                ? new TenantId(tenancy.tenant()).workspaceUnder(properties.workspace())
                : properties.workspace();
    }

    @Bean
    public AgentCommandLineRunner agentCommandLineRunner(
            SelfHealingOrchestrator orchestrator,
            PerformanceGate performanceGate,
            BuildReporterPort buildReporterPort,
            GateProperties gateProperties,
            TenancyProperties tenancy) {
        return new AgentCommandLineRunner(
                orchestrator, performanceGate, buildReporterPort,
                gateProperties.enabled(), commandLinePrincipal(tenancy));
    }

    /**
     * Who a run started from the command line is attributed to.
     *
     * <p>With tenancy off this is the local operator, and the audit trail says so. With it on the
     * run belongs to the configured tenant under a service identity, because a pipeline has no
     * person behind it and recording one would be a fiction in the provenance record.
     */
    static Principal commandLinePrincipal(TenancyProperties tenancy) {
        return tenancy.enabled()
                ? new Principal("cli", new TenantId(tenancy.tenant()), Set.of(Role.OPERATOR))
                : Principal.localOperator();
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
    public HealMemoryPort healMemoryPort(
            ObjectMapper objectMapper, AgentProperties properties, TenancyProperties tenancy) {
        return new JsonlHealMemoryAdapter(objectMapper,
                tenantWorkspace(properties, tenancy).resolve("heal-memory.jsonl"));
    }

    @Bean
    public RootCauseAnalyzerPort rootCauseAnalyzerPort(
            ChatClient jmeterChatClient,
            PromptCatalog promptCatalog,
            CostGovernorPort costGovernorPort) {
        return new SpringAiRootCauseAnalyzer(jmeterChatClient, promptCatalog, costGovernorPort);
    }

    @Bean
    public ResultStorePort resultStorePort(
            ObjectMapper objectMapper, AgentProperties properties, TenancyProperties tenancy) {
        return new JsonlResultStore(objectMapper,
                tenantWorkspace(properties, tenancy).resolve("run-history.jsonl"));
    }

    @Bean
    public RunLedgerPort runLedgerPort(
            ObjectMapper objectMapper, AgentProperties properties, TenancyProperties tenancy) {
        return new JsonlRunLedger(objectMapper,
                tenantWorkspace(properties, tenancy).resolve("run-ledger.jsonl"));
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
    public ControlPlaneController controlPlaneController(
            RunLedgerPort runLedgerPort, PrincipalResolver principalResolver) {
        return new ControlPlaneController(runLedgerPort, principalResolver);
    }

    /**
     * Decides who the control plane believes its callers are.
     *
     * <p>With tenancy off there is one operator on one machine, and demanding an identity header
     * would be ceremony with no security value. With it on, identity comes from headers an
     * authenticating proxy asserts — which is only sound because turning tenancy on is the
     * operator stating they have such a proxy.
     */
    @Bean
    @ConditionalOnWebApplication
    public PrincipalResolver principalResolver(TenancyProperties tenancy) {
        if (!tenancy.enabled()) {
            return new LocalOperatorPrincipalResolver();
        }
        return new TrustedHeaderPrincipalResolver(
                tenancy.userHeader(), tenancy.tenantHeader(), tenancy.rolesHeader());
    }

    @Bean
    public ProvenanceStorePort provenanceStorePort(
            ObjectMapper objectMapper, AgentProperties properties, TenancyProperties tenancy) {
        return new JsonlProvenanceStore(objectMapper,
                tenantWorkspace(properties, tenancy).resolve("provenance.jsonl"));
    }

    /**
     * Binds the signer, or the one that refuses.
     *
     * <p>No third option. A signer that quietly produced an empty signature would yield a
     * provenance chain that looks complete and proves nothing, discovered during the audit it
     * was meant to satisfy.
     */
    @Bean
    public ManifestSignerPort manifestSignerPort(TenancyProperties tenancy) {
        return tenancy.hasSigningKey()
                ? new HmacManifestSigner(tenancy.signingKey())
                : new UnconfiguredManifestSigner();
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
            ProvenanceStorePort provenanceStorePort,
            ManifestSignerPort manifestSignerPort,
            RegressionAnalyzer regressionAnalyzer,
            RootCauseAnalyzerPort rootCauseAnalyzerPort,
            AgentProperties properties,
            TenancyProperties tenancy) {
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
                provenanceStorePort,
                manifestSignerPort,
                regressionAnalyzer,
                rootCauseAnalyzerPort,
                new OrchestratorSettings(
                        properties.maxRetries(),
                        properties.strictCompliance(),
                        properties.recalledPrecedents(),
                        properties.historyDepth(),
                        properties.traceCorrelation(),
                        tenancy.promptRevision(),
                        properties.modelsByTurn().entrySet().stream().collect(
                                java.util.stream.Collectors.toMap(
                                        entry -> entry.getKey().name(), Map.Entry::getValue))));
    }
}
