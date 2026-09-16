package com.ai.jmeter.agent.adapter.cli;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
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

    public AgentCommandLineRunner(SelfHealingOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Override
    public void run(String... args) {
        Optional<String> mode = argumentValue(args, MODE_ARGUMENT);
        Optional<String> source = argumentValue(args, SOURCE_ARGUMENT);

        if (mode.isEmpty() || source.isEmpty()) {
            log.info("""
                    Autonomous JMeter Performance Testing Agent
                      Usage: --mode=<MODE> --source=<path> [--workload=<access log>]

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
                argumentValue(args, WORKLOAD_ARGUMENT).map(Path::of).orElse(null)));

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
    }

    private static Optional<String> argumentValue(String[] args, String prefix) {
        return Arrays.stream(args)
                .filter(arg -> arg.startsWith(prefix))
                .map(arg -> arg.substring(prefix.length()))
                .filter(value -> !value.isBlank())
                .findFirst();
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
