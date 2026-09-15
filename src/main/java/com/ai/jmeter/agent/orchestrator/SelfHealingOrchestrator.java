package com.ai.jmeter.agent.orchestrator;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.SelfHealingFailedException;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.domain.jmx.JmxRepairPlan;
import com.ai.jmeter.agent.domain.jmx.JmxValidationResult;
import com.ai.jmeter.agent.domain.redaction.CompliancePolicyViolationException;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import com.ai.jmeter.agent.domain.redaction.SecretCategory;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.JmxDocumentException;
import com.ai.jmeter.agent.port.JmxDocumentPort;
import com.ai.jmeter.agent.port.SensitiveDataRedactorPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import java.util.List;
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

    private final TrafficParserRegistry parserRegistry;
    private final JmeterAgentPort agent;
    private final ExecutionEnginePort executionEngine;
    private final WorkspacePort workspace;
    private final SensitiveDataRedactorPort redactor;
    private final JmxDocumentPort jmxDocument;
    private final int maxAttempts;
    private final boolean strictCompliance;

    /**
     * @param maxAttempts      total number of JMeter runs the agent may spend on one request,
     *                         counting the first. Must be at least one.
     * @param strictCompliance when true, a capture carrying regulated material is refused rather
     *                         than sent to the model in substituted form
     * @throws IllegalArgumentException if {@code maxAttempts} is below one
     */
    public SelfHealingOrchestrator(
            TrafficParserRegistry parserRegistry,
            JmeterAgentPort agent,
            ExecutionEnginePort executionEngine,
            WorkspacePort workspace,
            SensitiveDataRedactorPort redactor,
            JmxDocumentPort jmxDocument,
            int maxAttempts,
            boolean strictCompliance) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
        }
        this.parserRegistry = parserRegistry;
        this.agent = agent;
        this.executionEngine = executionEngine;
        this.workspace = workspace;
        this.redactor = redactor;
        this.jmxDocument = jmxDocument;
        this.maxAttempts = maxAttempts;
        this.strictCompliance = strictCompliance;
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

        String parsedTraffic = parserRegistry.parserFor(request.mode()).parse(request.sourceFile());
        log.debug("Minimized traffic payload is {} characters", parsedTraffic.length());

        // Redaction happens before any other processing: from here on, nothing downstream —
        // prompt, generated artifact or log line — can carry a live credential off this host.
        RedactionResult redaction = redactor.redact(parsedTraffic);
        enforceCompliancePolicy(redaction);
        workspace.writeSecretBindings(redaction.propertyBindings());

        warnIfJdbcDriverMissing(request);

        JmeterGenerationResult current =
                agent.generateScript(redaction.redactedPayload(), request.mode());
        log.info("Initial plan generated. Variables identified: {}", current.identifiedVariables());

        for (int attempt = 1; ; attempt++) {
            Attempt outcome = attemptOnce(current);
            ExecutionReport report = outcome.report();

            if (report.successful()) {
                log.info("Attempt {}/{} passed with {} samples and no failures",
                        attempt, maxAttempts, report.totalSamples());
                return new AgentRunOutcome(
                        request.mode(), current, outcome.artifacts(), report, attempt, redaction);
            }

            log.warn("Attempt {}/{} failed: status={} failedSamples={}",
                    attempt, maxAttempts, report.status(), report.failedSamples());

            if (attempt >= maxAttempts) {
                log.error("Retry budget exhausted after {} attempt(s); giving up", attempt);
                throw new SelfHealingFailedException(attempt, report);
            }

            current = repair(current, report);
        }
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
    private JmeterGenerationResult repair(JmeterGenerationResult current, ExecutionReport report) {
        JmxRepairPlan repairPlan = agent.proposeRepairs(
                summarize(current), report.errorDigest());

        if (repairPlan.requiresFullRewrite()) {
            log.info("Model asked for a full rewrite: {}", repairPlan.diagnosis());
            return agent.healScript(current.jmxXmlContent(), report.errorDigest());
        }

        try {
            String patched = jmxDocument.apply(current.jmxXmlContent(), repairPlan.mutations());
            log.info("Applied {} structural edit(s):\n{}",
                    repairPlan.mutations().size(), repairPlan.describe());
            return new JmeterGenerationResult(
                    patched,
                    current.csvTemplateContent(),
                    current.identifiedVariables(),
                    repairPlan.diagnosis());
        } catch (JmxDocumentException e) {
            log.warn("Structural repair could not be applied ({}); regenerating the plan instead",
                    e.getMessage());
            return agent.healScript(current.jmxXmlContent(), report.errorDigest());
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
     * Refuses a capture whose content the configured policy will not allow off the host, even
     * substituted. Deliberately fails the run rather than degrading silently — a compliance
     * control that can be missed without anyone noticing is not a control.
     */
    private void enforceCompliancePolicy(RedactionResult redaction) {
        if (!strictCompliance) {
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
