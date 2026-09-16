package com.ai.jmeter.agent.adapter.cli;

import com.ai.jmeter.agent.domain.eval.EvaluationScore;
import com.ai.jmeter.agent.domain.eval.EvaluationSuite;
import com.ai.jmeter.agent.orchestrator.EvaluationHarness;
import com.ai.jmeter.agent.port.EvaluationCorpusPort;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;

/**
 * Driving adapter: runs the golden corpus and reports how the agent scored.
 *
 * <p>Invoked as {@code --evaluate=corpus/suite.yaml}, and inert otherwise. A separate runner from
 * the one that drives a load test because the two answer different questions with different
 * costs: one produces a test plan for a service, the other measures whether the agent is still
 * any good at producing them.
 */
public final class EvaluationCommandLineRunner implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(EvaluationCommandLineRunner.class);

    static final String EVALUATE_ARGUMENT = "--evaluate=";

    private final EvaluationCorpusPort corpus;
    private final EvaluationHarness harness;

    public EvaluationCommandLineRunner(EvaluationCorpusPort corpus, EvaluationHarness harness) {
        this.corpus = corpus;
        this.harness = harness;
    }

    @Override
    public void run(String... args) {
        Optional<String> suiteFile = CommandLineArguments.value(args, EVALUATE_ARGUMENT);
        if (suiteFile.isEmpty()) {
            return;
        }

        EvaluationSuite suite = corpus.load(Path.of(suiteFile.get()));
        EvaluationScore score = harness.evaluate(suite);

        log.info("""
                Evaluation complete.
                  Revision              : {}
                  Cases                 : {}
                  First-attempt success : {}%
                  Fully correct         : {}%
                  Mean heal cycles      : {}
                  Mean token cost       : {}""",
                score.revision(),
                score.caseCount(),
                Math.round(score.firstAttemptSuccessRate() * 100),
                Math.round(score.correctnessRate() * 100),
                "%.2f".formatted(score.meanHealCycles()),
                Math.round(score.meanTokenCost()));
    }
}
