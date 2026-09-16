package com.ai.jmeter.agent.orchestrator;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.SelfHealingFailedException;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.domain.analysis.RegressionAnalyzer;
import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.jmx.JmxMutation;
import com.ai.jmeter.agent.domain.jmx.JmxRepairPlan;
import com.ai.jmeter.agent.domain.jmx.JmxValidationResult;
import com.ai.jmeter.agent.domain.memory.HealPrecedent;
import com.ai.jmeter.agent.domain.redaction.CompliancePolicyViolationException;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import com.ai.jmeter.agent.domain.redaction.SecretCategory;
import com.ai.jmeter.agent.domain.results.PlanFingerprint;
import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.domain.workload.WorkloadModel;
import com.ai.jmeter.agent.port.CostGovernorPort;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.HealMemoryPort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.JmxDocumentException;
import com.ai.jmeter.agent.port.JmxDocumentPort;
import com.ai.jmeter.agent.port.ResultStorePort;
import com.ai.jmeter.agent.port.SensitiveDataRedactorPort;
import com.ai.jmeter.agent.port.WorkloadProfilerPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The agentic loop: <em>generate &rarr; execute &rarr; analyze &rarr; heal &rarr; repeat</em>.
 *
 * <p>This is the only component that knows the workflow is autonomous. It holds the invariant
 * that makes the agent trustworthy: a plan is never considered finished because the model says
 * so, only because a real JMeter run reported a 100% success rate. Every failing run is turned
 * into evidence and fed back to the model, and the budget is bounded so a plan the model cannot
 * fix surfaces as a failure instead of looping forever.
 *
 * <p>Deliberately free of framework annotations — it is wired by explicit configuration, so the
 * core workflow has no compile-time dependency on Spring.
 */
public final class SelfHealingOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SelfHealingOrchestrator.class);

    private static final String TRACEPARENT_HEADER = "traceparent";

    /**
     * A W3C traceparent generated per sample by JMeter itself. Version 00, a fresh 128-bit trace
     * id and 64-bit span id as lowercase hex, sampled flag set — so every request the plan issues
     * appears in the server's tracing backend and its latency can be attributed to a real span.
     */
    private static final String TRACEPARENT_VALUE =
            "00-${__RandomString(32,0123456789abcdef)}-${__RandomString(16,0123456789abcdef)}-01";

    private final TrafficParserRegistry parserRegistry;
    private final JmeterAgentPort agent;
    private final ExecutionEnginePort executionEngine;
    private final WorkspacePort workspace;
    private final SensitiveDataRedactorPort redactor;
    private final JmxDocumentPort jmxDocument;
    private final HealMemoryPort memory;
    private final CostGovernorPort costGovernor;
    private final WorkloadProfilerPort workloadProfiler;
    private final ResultStorePort resultStore;
    private final RegressionAnalyzer regressionAnalyzer;
    private final OrchestratorSettings settings;
    private int workloadConcurrency = 1;

    /**
     * @param settings the policy governing retries, compliance, memory and observability
     */
    public SelfHealingOrchestrator(
            TrafficParserRegistry parserRegistry,
            JmeterAgentPort agent,
            ExecutionEnginePort executionEngine,
            WorkspacePort workspace,
            SensitiveDataRedactorPort redactor,
            JmxDocumentPort jmxDocument,
            HealMemoryPort memory,
            CostGovernorPort costGovernor,
            WorkloadProfilerPort workloadProfiler,
            ResultStorePort resultStore,
            RegressionAnalyzer regressionAnalyzer,
            OrchestratorSettings settings) {
        this.parserRegistry = parserRegistry;
        this.agent = agent;
        this.executionEngine = executionEngine;
        this.workspace = workspace;
        this.redactor = redactor;
        this.jmxDocument = jmxDocument;
        this.memory = memory;
        this.costGovernor = costGovernor;
        this.workloadProfiler = workloadProfiler;
        this.resultStore = resultStore;
        this.regressionAnalyzer = regressionAnalyzer;
        this.settings = settings;
    }

    /**
     * Drives one request to a passing test plan.
     *
     * @param request the capture to learn from and the mode to learn it in
     * @return the outcome of the run that finally passed
     * @throws SelfHealingFailedException if the retry budget is exhausted while the plan still
     *                                    fails
     */
    public AgentRunOutcome run(AgentRunRequest request) {
        log.info("Starting agentic run in {} mode from {}", request.mode(), request.sourceFile());
        costGovernor.beginRun();

        String parsedTraffic = parserRegistry.parserFor(request.mode()).parse(request.sourceFile());
        log.debug("Minimized traffic payload is {} characters", parsedTraffic.length());

        // Redaction happens before any other processing: from here on, nothing downstream —
        // prompt, generated artifact or log line — can carry a live credential off this host.
        RedactionResult redaction = redactor.redact(parsedTraffic);
        enforceCompliancePolicy(redaction);
        workspace.writeSecretBindings(redaction.propertyBindings());

        warnIfJdbcDriverMissing(request);

        WorkloadModel workload = profileWorkload(request);
        workloadConcurrency = workload.concurrentUsers();

        JmeterGenerationResult current = agent.generateScript(
                briefFor(redaction.redactedPayload(), workload), request.mode());
        log.info("Initial plan generated. Variables identified: {}", current.identifiedVariables());

        current = applyPostGenerationEdits(current, workload);

        AppliedRepair lastRepair = null;

        for (int attempt = 1; ; attempt++) {
            Attempt outcome = attemptOnce(current);
            ExecutionReport report = outcome.report();

            if (report.successful()) {
                log.info("Attempt {}/{} passed with {} samples and no failures",
                        attempt, settings.maxAttempts(), report.totalSamples());
                recordPrecedent(lastRepair, true);
                log.info("Run cost: {}", costGovernor.currentCost().describe());
                return new AgentRunOutcome(
                        request.mode(), current, outcome.artifacts(), report, attempt,
                        redaction, costGovernor.currentCost(),
                        analyzeResults(request, current, report));
            }

            log.warn("Attempt {}/{} failed: status={} failedSamples={}",
                    attempt, settings.maxAttempts(), report.status(), report.failedSamples());

            // The repair that preceded this attempt did not work. Recording that is as valuable
            // as recording a success: it stops the next run proposing the same dead end.
            recordPrecedent(lastRepair, false);

            if (attempt >= settings.maxAttempts()) {
                log.error("Retry budget exhausted after {} attempt(s); giving up", attempt);
                throw new SelfHealingFailedException(attempt, report);
            }

            RepairOutcome repaired = repair(current, report);
            current = repaired.plan();
            lastRepair = repaired.applied();
        }
    }

    /**
     * Infers the load shape to reproduce, when telemetry was supplied.
     *
     * <p>A profiling failure is not fatal: the run degrades to a single-user correctness pass,
     * which is still a useful answer, rather than losing the plan entirely over a malformed log.
     */
    private WorkloadModel profileWorkload(AgentRunRequest request) {
        return request.telemetry()
                .map(telemetry -> {
                    try {
                        return workloadProfiler.profile(telemetry);
                    } catch (RuntimeException e) {
                        log.warn("Could not profile workload from {} ({}); running as a smoke test",
                                telemetry, e.getMessage());
                        return WorkloadModel.smokeTest();
                    }
                })
                .orElseGet(WorkloadModel::smokeTest);
    }

    /**
     * Appends the workload shape to what the model reasons over, so the endpoint mix informs which
     * calls the plan emphasizes rather than only how many threads run it.
     */
    private String briefFor(String payload, WorkloadModel workload) {
        if (!workload.isCredible()) {
            return payload;
        }
        return payload + "\n\nWorkload shape observed in production:\n" + workload.describe();
    }

    /**
     * Applies the edits that are arithmetic rather than judgement: the inferred thread count, and
     * trace propagation headers.
     *
     * <p>Done as structural edits rather than asked for in the prompt because a model reproducing
     * a number or a fixed header format in XML will sometimes round the first and mistype the
     * second, and neither failure is visible until the results are already wrong.
     */
    private JmeterGenerationResult applyPostGenerationEdits(
            JmeterGenerationResult plan, WorkloadModel workload) {
        List<JmxMutation> edits = new ArrayList<>();

        if (workload.isCredible()) {
            edits.add(new JmxMutation.ConfigureThreadGroup(
                    workload.concurrentUsers(), workload.rampUpSeconds(), 1));
        }
        if (settings.traceCorrelation()) {
            edits.add(new JmxMutation.SetHeader("", TRACEPARENT_HEADER, TRACEPARENT_VALUE));
        }
        if (edits.isEmpty()) {
            return plan;
        }

        try {
            String shaped = jmxDocument.apply(plan.jmxXmlContent(), edits);
            edits.forEach(edit -> log.info("Applied post-generation edit: {}", edit.describe()));
            return new JmeterGenerationResult(
                    shaped, plan.csvTemplateContent(),
                    plan.identifiedVariables(), plan.executionRationale());
        } catch (JmxDocumentException e) {
            log.warn("Could not apply post-generation edits ({}); leaving the plan as generated",
                    e.getMessage());
            return plan;
        }
    }

    /**
     * Records what the passing run measured and compares it to the history of the same plan.
     *
     * <p>Recording is best-effort: a run that genuinely passed must not be reported as failed
     * because its history file was unwritable.
     */
    private RunAnalysis analyzeResults(
            AgentRunRequest request, JmeterGenerationResult plan, ExecutionReport report) {

        RunSummary summary = new RunSummary(
                UUID.randomUUID().toString(),
                PlanFingerprint.of(request.mode(), samplerNamesOf(plan)),
                Instant.now(),
                report.statisticsByLabel(),
                workloadConcurrency,
                throughputOf(report));

        try {
            List<RunSummary> history = resultStore.history(
                    summary.planFingerprint(), settings.historyDepth());
            resultStore.record(summary);

            RunAnalysis analysis = new RunAnalysis(
                    summary, regressionAnalyzer.analyze(summary, history));
            log.info("Result analysis:\n{}", analysis.describe());
            return analysis;
        } catch (RuntimeException e) {
            log.warn("Could not record or compare run results ({}); the run itself still passed",
                    e.getMessage());
            return RunAnalysis.withoutComparison(summary);
        }
    }

    private List<String> samplerNamesOf(JmeterGenerationResult plan) {
        try {
            return jmxDocument.describe(plan.jmxXmlContent()).samplerNames();
        } catch (JmxDocumentException e) {
            return List.of();
        }
    }

    /**
     * @return samples per second, derived from the mean latency and concurrency rather than wall
     * clock, which the execution engine does not report
     */
    private static double throughputOf(ExecutionReport report) {
        double meanMillis = report.statisticsByLabel().values().stream()
                .mapToDouble(statistics -> statistics.meanMillis())
                .average()
                .orElse(0);
        return meanMillis <= 0 ? 0 : 1000.0 / meanMillis;
    }

    /** Files a repair against the failure it was meant to fix, once the verdict is in. */
    private void recordPrecedent(AppliedRepair repair, boolean worked) {
        if (repair == null) {
            return;
        }
        memory.remember(new HealPrecedent(
                repair.signature(), repair.diagnosis(), repair.edits(), worked));
    }

    /**
     * Validates, then executes only if validation passed.
     *
     * <p>Skipping execution for a plan that cannot work is the cheapest win in the loop: a JMeter
     * startup costs tens of seconds, while catching an unresolved variable reference costs
     * microseconds and yields strictly better evidence for the repair turn than a wall of 401s.
     *
     * @return the verdict, with {@code artifacts} absent when validation short-circuited the run
     */
    private Attempt attemptOnce(JmeterGenerationResult plan) {
        JmxValidationResult validation = jmxDocument.validate(plan.jmxXmlContent());

        if (!validation.isValid()) {
            log.warn("Pre-flight validation rejected the plan, skipping execution:\n{}",
                    validation.describe());
            return new Attempt(ExecutionReport.validationFailure(validation.describe()), null);
        }
        if (validation.hasWarnings()) {
            log.warn("Pre-flight warnings:\n{}", validation.describe());
        }

        WorkspaceArtifacts artifacts = workspace.write(plan);
        return new Attempt(executionEngine.execute(artifacts.jmxScript()), artifacts);
    }

    /**
     * Asks for structural edits first and only regenerates the plan when edits cannot express the
     * fix, so working correlation survives a repair instead of being rediscovered each turn.
     */
    private RepairOutcome repair(JmeterGenerationResult current, ExecutionReport report) {
        String signature = report.failureSignature();
        JmxRepairPlan repairPlan = agent.proposeRepairs(
                summarize(current), report.errorDigest(), memory.recall(signature, settings.recalledPrecedents()));

        if (repairPlan.requiresFullRewrite()) {
            log.info("Model asked for a full rewrite: {}", repairPlan.diagnosis());
            return RepairOutcome.rewritten(
                    agent.healScript(current.jmxXmlContent(), report.errorDigest()));
        }

        try {
            String patched = jmxDocument.apply(current.jmxXmlContent(), repairPlan.mutations());
            log.info("Applied {} structural edit(s):\n{}",
                    repairPlan.mutations().size(), repairPlan.describe());
            return new RepairOutcome(
                    new JmeterGenerationResult(
                            patched,
                            current.csvTemplateContent(),
                            current.identifiedVariables(),
                            repairPlan.diagnosis()),
                    new AppliedRepair(
                            signature,
                            repairPlan.diagnosis(),
                            repairPlan.mutations().stream().map(JmxMutation::describe).toList()));
        } catch (JmxDocumentException e) {
            log.warn("Structural repair could not be applied ({}); regenerating the plan instead",
                    e.getMessage());
            return RepairOutcome.rewritten(
                    agent.healScript(current.jmxXmlContent(), report.errorDigest()));
        }
    }

    /**
     * Summarizes the plan for the repair turn, falling back to the raw XML when the plan is too
     * malformed to parse — which is itself the signal the model needs.
     */
    private String summarize(JmeterGenerationResult plan) {
        try {
            return jmxDocument.describe(plan.jmxXmlContent()).describe();
        } catch (JmxDocumentException e) {
            return "Plan could not be parsed: " + e.getMessage();
        }
    }

    /** @param artifacts null when pre-flight validation prevented the plan from being written. */
    private record Attempt(ExecutionReport report, WorkspaceArtifacts artifacts) {
    }

    /**
     * @param applied null for a full rewrite, which has no discrete edits worth remembering —
     *                "the model wrote a different plan" is not advice the next run can act on
     */
    private record RepairOutcome(JmeterGenerationResult plan, AppliedRepair applied) {

        static RepairOutcome rewritten(JmeterGenerationResult plan) {
            return new RepairOutcome(plan, null);
        }
    }

    /** A structural repair awaiting the verdict of the run that follows it. */
    private record AppliedRepair(String signature, String diagnosis, List<String> edits) {
    }

    /**
     * Refuses a capture whose content the configured policy will not allow off the host, even
     * substituted. Deliberately fails the run rather than degrading silently — a compliance
     * control that can be missed without anyone noticing is not a control.
     */
    private void enforceCompliancePolicy(RedactionResult redaction) {
        if (!settings.strictCompliance()) {
            return;
        }
        List<SecretCategory> violations = redaction.strictPolicyViolations();
        if (!violations.isEmpty()) {
            throw new CompliancePolicyViolationException(violations);
        }
    }

    /**
     * JDBC plans fail at connection time without a driver on the JMeter classpath, and no amount
     * of re-prompting can fix that from inside the test plan. Warn loudly rather than silently
     * burning the retry budget.
     */
    private void warnIfJdbcDriverMissing(AgentRunRequest request) {
        if (request.mode().requiresJdbcDriver() && !workspace.jdbcDriverAvailable()) {
            log.warn("SQL mode requested but no JDBC driver JAR was found on the JMeter "
                    + "classpath. Place a driver (for example mysql-connector-j.jar) in "
                    + "JMeter's lib/ folder, otherwise every JDBC sampler will fail to connect.");
        }
    }
}
