package com.ai.jmeter.agent.orchestrator;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.SelfHealingFailedException;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.WorkspacePort;
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
    private final int maxAttempts;

    /**
     * @param maxAttempts total number of JMeter runs the agent may spend on one request,
     *                    counting the first. Must be at least one.
     * @throws IllegalArgumentException if {@code maxAttempts} is below one
     */
    public SelfHealingOrchestrator(
            TrafficParserRegistry parserRegistry,
            JmeterAgentPort agent,
            ExecutionEnginePort executionEngine,
            WorkspacePort workspace,
            int maxAttempts) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
        }
        this.parserRegistry = parserRegistry;
        this.agent = agent;
        this.executionEngine = executionEngine;
        this.workspace = workspace;
        this.maxAttempts = maxAttempts;
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

        warnIfJdbcDriverMissing(request);

        JmeterGenerationResult current = agent.generateScript(parsedTraffic, request.mode());
        log.info("Initial plan generated. Variables identified: {}", current.identifiedVariables());

        for (int attempt = 1; ; attempt++) {
            WorkspaceArtifacts artifacts = workspace.write(current);
            ExecutionReport report = executionEngine.execute(artifacts.jmxScript());

            if (report.successful()) {
                log.info("Attempt {}/{} passed with {} samples and no failures",
                        attempt, maxAttempts, report.totalSamples());
                return new AgentRunOutcome(
                        request.mode(), current, artifacts, report, attempt);
            }

            log.warn("Attempt {}/{} failed: status={} failedSamples={}",
                    attempt, maxAttempts, report.status(), report.failedSamples());

            if (attempt >= maxAttempts) {
                log.error("Retry budget exhausted after {} attempt(s); giving up", attempt);
                throw new SelfHealingFailedException(attempt, report);
            }

            log.info("Re-prompting the model to repair the plan");
            current = agent.healScript(current.jmxXmlContent(), report.errorDigest());
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
