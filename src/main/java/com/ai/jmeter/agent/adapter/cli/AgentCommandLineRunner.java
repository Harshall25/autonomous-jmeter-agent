package com.ai.jmeter.agent.adapter.cli;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ci.GateVerdict;
import com.ai.jmeter.agent.domain.ci.PerformanceGate;
import com.ai.jmeter.agent.domain.ci.PerformanceGateFailedException;
import com.ai.jmeter.agent.domain.governance.Principal;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
import com.ai.jmeter.agent.port.BuildReporterPort;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;

/**
 * Driving adapter: the command line entry point into the agentic workflow.
 *
 * <p>Invoked as {@code --mode=API --source=/captures/checkout.har}. Started without those
 * arguments the application boots and prints usage, which is what makes it safe to run the
 * context up (in tests, or to verify configuration) without kicking off a load test.
 *
 * <p>Failures are allowed to propagate: an unattended agent that exits zero after failing to
 * produce a working plan would be worse than useless in a pipeline.
 */
public final class AgentCommandLineRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AgentCommandLineRunner.class);

    private static final String MODE_ARGUMENT = "--mode=";
    private static final String SOURCE_ARGUMENT = "--source=";
    private static final String WORKLOAD_ARGUMENT = "--workload=";

    private final SelfHealingOrchestrator orchestrator;
    private final PerformanceGate performanceGate;
    private final BuildReporterPort buildReporter;
    private final boolean gateEnabled;
    private final Principal principal;

    /**
     * @param gateEnabled when true, the run is judged against the pipeline's performance policy
     *                    and a breach fails the process; off by default so that adding the agent
     *                    to a pipeline never silently starts blocking merges
     * @param principal   who runs started here are attributed to, in the provenance record
     */
    public AgentCommandLineRunner(
            SelfHealingOrchestrator orchestrator,
            PerformanceGate performanceGate,
            BuildReporterPort buildReporter,
            boolean gateEnabled,
            Principal principal) {
        this.orchestrator = orchestrator;
        this.performanceGate = performanceGate;
        this.buildReporter = buildReporter;
        this.gateEnabled = gateEnabled;
        this.principal = principal;
    }

    @Override
    public void run(String... args) {
        if (CommandLineArguments.value(args, EvaluationCommandLineRunner.EVALUATE_ARGUMENT)
                .isPresent()) {
            // The corpus runner owns this invocation; printing usage over its report would only
            // suggest the agent had failed to understand the command.
            return;
        }

        Optional<String> mode = CommandLineArguments.value(args, MODE_ARGUMENT);
        Optional<String> source = CommandLineArguments.value(args, SOURCE_ARGUMENT);

        if (mode.isEmpty() || source.isEmpty()) {
            log.info("""
                    Autonomous JMeter Performance Testing Agent
                      Usage: --mode=<MODE> --source=<path> [--workload=<access log>]

                      Or:    --evaluate=<suite.yaml> to score the agent against a golden corpus.

                      API      ingests an HTTP Archive capture of real traffic.
                      OPENAPI  ingests an OpenAPI / Swagger document (JSON or YAML).
                      POSTMAN  ingests a Postman collection export.
                      SQL      ingests a database slow query log and builds a JDBC plan.
                      STREAMING ingests a broker topic manifest and builds a messaging plan.

                      --workload is optional. Supply a production access log and the agent infers
                      the concurrency, ramp-up and endpoint mix to reproduce; without it the plan
                      runs as a single-user correctness pass.""");
            return;
        }

        AgentRunOutcome outcome = orchestrator.run(new AgentRunRequest(
                parseMode(mode.get()),
                Path.of(source.get()),
                CommandLineArguments.value(args, WORKLOAD_ARGUMENT)
                        .map(Path::of).orElse(null),
                principal));

        log.info("""
                Agentic run complete after {} attempt(s).
                  Test plan : {}
                  Test data : {}
                  Samples   : {}, all passing
                  Variables : {}
                  Redacted  : {}
                  Cost      : {}
                  Healing   : {}
                  Rationale : {}""",
                outcome.attempts(),
                outcome.artifacts().jmxScript(),
                outcome.artifacts().csvData(),
                outcome.report().totalSamples(),
                outcome.script().identifiedVariables(),
                outcome.redaction().countsByCategory(),
                outcome.cost().describe(),
                outcome.journal().churn(),
                outcome.script().executionRationale());

        applyPerformanceGate(outcome);
    }

    /**
     * Judges the run against the pipeline's policy, publishes the report either way, and fails
     * the process on a breach.
     *
     * <p>The report is published before the failure is raised, so a blocked build still leaves
     * the reviewer the percentile table that explains the block.
     */
    private void applyPerformanceGate(AgentRunOutcome outcome) {
        if (!gateEnabled) {
            return;
        }
        GateVerdict verdict = performanceGate.judge(outcome);
        buildReporter.publish(verdict, performanceGate.comment(outcome, verdict));

        if (!verdict.passed()) {
            log.error("{}", verdict.describe());
            throw new PerformanceGateFailedException(verdict);
        }
        log.info("{}", verdict.describe());
    }

    private static ExecutionMode parseMode(String rawMode) {
        try {
            return ExecutionMode.valueOf(rawMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown mode '%s'. Valid modes are %s."
                            .formatted(rawMode, Arrays.toString(ExecutionMode.values())), e);
        }
    }
}
