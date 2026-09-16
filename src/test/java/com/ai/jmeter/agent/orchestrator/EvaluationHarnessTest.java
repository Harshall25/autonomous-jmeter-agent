package com.ai.jmeter.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.SelfHealingFailedException;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import com.ai.jmeter.agent.domain.cost.AgentTurn;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.cost.TokenUsage;
import com.ai.jmeter.agent.domain.eval.EvaluationCase;
import com.ai.jmeter.agent.domain.eval.EvaluationScore;
import com.ai.jmeter.agent.domain.eval.EvaluationSuite;
import com.ai.jmeter.agent.domain.eval.StructuralExpectation;
import com.ai.jmeter.agent.domain.jmx.JmxStructure;
import com.ai.jmeter.agent.domain.journal.HealJournal;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.port.JmxDocumentException;
import com.ai.jmeter.agent.port.JmxDocumentPort;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("EvaluationHarness")
class EvaluationHarnessTest {

    @Mock
    private SelfHealingOrchestrator orchestrator;

    @Mock
    private JmxDocumentPort jmxDocument;

    private EvaluationHarness harness;

    @BeforeEach
    void setUp() {
        harness = new EvaluationHarness(orchestrator, jmxDocument);
        when(jmxDocument.describe(anyString())).thenReturn(new JmxStructure(
                List.of("login", "orders"), List.of("auth_token"), List.of("auth_token")));
    }

    private static AgentRunOutcome outcomeAfter(int attempts, long tokens) {
        return new AgentRunOutcome(
                ExecutionMode.API,
                new JmeterGenerationResult("<plan/>", "user\nalice", List.of("user"), "why"),
                new WorkspaceArtifacts(Path.of("auto_test.jmx"), Path.of("test_data.csv")),
                ExecutionReport.success(10, ""),
                attempts,
                RedactionResult.clean("[]"),
                RunCost.empty(0).plus(AgentTurn.GENERATION, new TokenUsage(tokens, 0)),
                RunAnalysis.withoutComparison(new RunSummary(
                        "run-1", "plan-a", Instant.EPOCH, Map.of(), 1, 0)),
                HealJournal.empty());
    }

    private static EvaluationCase caseNamed(String name, StructuralExpectation... expectations) {
        return new EvaluationCase(
                name, ExecutionMode.API, Path.of(name + ".har"), null, List.of(expectations));
    }

    private static EvaluationSuite suiteOf(EvaluationCase... cases) {
        return new EvaluationSuite("prompts@v3", List.of(cases));
    }

    @Test
    @DisplayName("scores a case whose plan met every expectation")
    void scoresACorrectCase() {
        when(orchestrator.run(any())).thenReturn(outcomeAfter(1, 1200));

        EvaluationScore score = harness.evaluate(suiteOf(caseNamed("checkout",
                new StructuralExpectation.ExercisesSampler("login"),
                new StructuralExpectation.CorrelatesVariable("auth_token"))));

        assertThat(score.revision()).isEqualTo("prompts@v3");
        assertThat(score.cases()).singleElement().satisfies(result -> {
            assertThat(result.fullyCorrect()).isTrue();
            assertThat(result.firstAttemptSuccess()).isTrue();
            assertThat(result.satisfied()).isEqualTo(2);
            assertThat(result.tokens()).isEqualTo(1200);
        });
    }

    @Test
    @DisplayName("names the expectations a passing plan failed to satisfy")
    void namesUnmetExpectations() {
        // The plan ran green. It is still wrong, and this is the only thing that says so.
        when(orchestrator.run(any())).thenReturn(outcomeAfter(1, 900));

        EvaluationScore score = harness.evaluate(suiteOf(caseNamed("checkout",
                new StructuralExpectation.ExercisesSampler("login"),
                new StructuralExpectation.CorrelatesVariable("session_id"))));

        assertThat(score.cases()).singleElement().satisfies(result -> {
            assertThat(result.completed()).isTrue();
            assertThat(result.fullyCorrect()).isFalse();
            assertThat(result.satisfied()).isEqualTo(1);
            assertThat(result.unmet()).containsExactly("correlates the variable 'session_id'");
        });
    }

    @Test
    @DisplayName("drives each case through the agent with its own mode and capture")
    void drivesEachCase() {
        when(orchestrator.run(any())).thenReturn(outcomeAfter(1, 0));
        EvaluationCase sqlCase = new EvaluationCase(
                "slow-queries", ExecutionMode.SQL, Path.of("slow.log"),
                Path.of("access.log"), List.of());

        harness.evaluate(suiteOf(caseNamed("checkout"), sqlCase));

        ArgumentCaptor<AgentRunRequest> requests =
                ArgumentCaptor.forClass(AgentRunRequest.class);
        verify(orchestrator, times(2)).run(requests.capture());
        assertThat(requests.getAllValues().get(0).mode()).isEqualTo(ExecutionMode.API);
        assertThat(requests.getAllValues().get(1).mode()).isEqualTo(ExecutionMode.SQL);
        assertThat(requests.getAllValues().get(1).telemetry())
                .contains(Path.of("access.log"));
    }

    @Test
    @DisplayName("records a case that never reached a passing plan, keeping its attempt count")
    void recordsAnExhaustedCase() {
        when(orchestrator.run(any())).thenThrow(new SelfHealingFailedException(
                3, ExecutionReport.processFailure("jmeter exited 1")));

        EvaluationScore score = harness.evaluate(suiteOf(caseNamed("checkout")));

        assertThat(score.cases()).singleElement().satisfies(result -> {
            assertThat(result.completed()).isFalse();
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(result.failure()).contains("never reached a passing plan");
        });
        assertThat(score.completionRate()).isZero();
    }

    @Test
    @DisplayName("records a case that blew up rather than losing the rest of the corpus")
    void oneBrokenCaseDoesNotAbortTheSuite() {
        // The suite exists to measure how often the agent fails; aborting on the first failure
        // would destroy the measurement exactly when it matters.
        when(orchestrator.run(any()))
                .thenThrow(new IllegalStateException("capture.har does not exist"))
                .thenReturn(outcomeAfter(1, 100));

        EvaluationScore score = harness.evaluate(
                suiteOf(caseNamed("missing"), caseNamed("checkout")));

        assertThat(score.caseCount()).isEqualTo(2);
        assertThat(score.cases().get(0).failure()).isEqualTo("capture.har does not exist");
        assertThat(score.cases().get(1).completed()).isTrue();
        assertThat(score.completionRate()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("records a plan that passed a real run but cannot be parsed back")
    void recordsAnUnparseablePlan() {
        when(orchestrator.run(any())).thenReturn(outcomeAfter(2, 700));
        when(jmxDocument.describe(anyString()))
                .thenThrow(new JmxDocumentException("Content is not allowed in prolog"));

        EvaluationScore score = harness.evaluate(suiteOf(caseNamed("checkout")));

        assertThat(score.cases()).singleElement().satisfies(result -> {
            assertThat(result.completed()).isFalse();
            assertThat(result.attempts()).isEqualTo(2);
            assertThat(result.tokens()).isEqualTo(700);
            assertThat(result.failure()).contains("could not be parsed");
        });
    }

    @Test
    @DisplayName("scores a case that asserts nothing as correct, since nothing was asserted")
    void caseWithNoExpectationsIsCorrect() {
        when(orchestrator.run(any())).thenReturn(outcomeAfter(2, 400));

        EvaluationScore score = harness.evaluate(suiteOf(caseNamed("smoke")));

        assertThat(score.cases()).singleElement().satisfies(result -> {
            assertThat(result.fullyCorrect()).isTrue();
            assertThat(result.firstAttemptSuccess()).isFalse();
            assertThat(result.healCycles()).isEqualTo(1);
        });
    }
}
