package com.ai.jmeter.agent.orchestrator;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.SelfHealingFailedException;
import com.ai.jmeter.agent.domain.eval.CaseResult;
import com.ai.jmeter.agent.domain.eval.EvaluationCase;
import com.ai.jmeter.agent.domain.eval.EvaluationScore;
import com.ai.jmeter.agent.domain.eval.EvaluationSuite;
import com.ai.jmeter.agent.domain.eval.StructuralExpectation;
import com.ai.jmeter.agent.domain.jmx.JmxStructure;
import com.ai.jmeter.agent.port.JmxDocumentException;
import com.ai.jmeter.agent.port.JmxDocumentPort;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the golden corpus through the agent and scores what came back.
 *
 * <p>The regression test for the parts of the agent a unit test cannot reach. Every other test in
 * this project pins code; this one pins <em>behaviour</em>, which is what a prompt edit or a model
 * upgrade actually changes. Without it, a one-word change to a system prompt can halve the
 * first-attempt success rate and the build stays green.
 *
 * <p>Deliberately not a JUnit test. It costs real model calls and real JMeter runs, so it belongs
 * on the schedule a team chooses — before a prompt change ships, and after a model is bumped —
 * rather than on every commit.
 */
public final class EvaluationHarness {

    private static final Logger log = LoggerFactory.getLogger(EvaluationHarness.class);

    private final SelfHealingOrchestrator orchestrator;
    private final JmxDocumentPort jmxDocument;

    public EvaluationHarness(SelfHealingOrchestrator orchestrator, JmxDocumentPort jmxDocument) {
        this.orchestrator = orchestrator;
        this.jmxDocument = jmxDocument;
    }

    /**
     * Runs every case and scores the corpus as a whole.
     *
     * @param suite the corpus, and the revision its scores are filed under
     * @return the scores, one per case plus the aggregate
     */
    public EvaluationScore evaluate(EvaluationSuite suite) {
        log.info("Evaluating {} over {} case(s)", suite.revision(), suite.cases().size());

        List<CaseResult> results = new ArrayList<>();
        for (EvaluationCase evaluationCase : suite.cases()) {
            log.info("Case: {}", evaluationCase.describe());
            CaseResult result = run(evaluationCase);
            log.info("  {}", result.describe());
            results.add(result);
        }

        EvaluationScore score = new EvaluationScore(suite.revision(), results);
        log.info("{}", score.describe());
        return score;
    }

    /**
     * Runs one case, converting any failure into a recorded result.
     *
     * <p>A case that blows up is data, not an interruption: the suite exists to measure how often
     * the agent fails, so letting the first failure abort the remaining cases would destroy the
     * measurement exactly when it matters.
     */
    private CaseResult run(EvaluationCase evaluationCase) {
        try {
            AgentRunOutcome outcome = orchestrator.run(new AgentRunRequest(
                    evaluationCase.mode(), evaluationCase.sourceFile(), evaluationCase.telemetry()));
            return score(evaluationCase, outcome);
        } catch (SelfHealingFailedException e) {
            return CaseResult.failed(evaluationCase.name(), e.attempts(), 0,
                    "never reached a passing plan: " + e.getMessage());
        } catch (RuntimeException e) {
            return CaseResult.failed(evaluationCase.name(), 0, 0, e.getMessage());
        }
    }

    /** Checks the produced plan against what the case said the agent should have worked out. */
    private CaseResult score(EvaluationCase evaluationCase, AgentRunOutcome outcome) {
        JmxStructure structure;
        try {
            structure = jmxDocument.describe(outcome.script().jmxXmlContent());
        } catch (JmxDocumentException e) {
            // A plan that passed a real JMeter run but cannot be parsed here is worth knowing
            // about, and is not the same failure as never producing one.
            return CaseResult.failed(evaluationCase.name(), outcome.attempts(),
                    outcome.cost().totalTokens(),
                    "produced a plan that could not be parsed: " + e.getMessage());
        }

        List<String> unmet = evaluationCase.expectations().stream()
                .filter(expectation ->
                        !expectation.isSatisfiedBy(outcome.script(), structure))
                .map(StructuralExpectation::describe)
                .toList();

        return new CaseResult(
                evaluationCase.name(),
                true,
                outcome.attempts(),
                outcome.cost().totalTokens(),
                evaluationCase.expectations().size() - unmet.size(),
                unmet,
                "");
    }
}
