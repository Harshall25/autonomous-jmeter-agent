package com.ai.jmeter.agent.domain.eval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.jmx.JmxStructure;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Agent evaluation")
class EvaluationScoringTest {

    private static final JmeterGenerationResult PLAN = new JmeterGenerationResult(
            "<plan/>", "user,password\nalice,secret", List.of("auth_token"), "rationale");

    private static final JmxStructure STRUCTURE = new JmxStructure(
            List.of("POST /v1/login", "GET /v1/orders"),
            List.of("auth_token", "user"),
            List.of("auth_token"));

    private static CaseResult passed(String name, int attempts, long tokens) {
        return new CaseResult(name, true, attempts, tokens, 2, List.of(), "");
    }

    @Nested
    @DisplayName("structural expectations")
    class Expectations {

        @Test
        @DisplayName("matches a sampler by fragment, since the model names them as it pleases")
        void matchesSamplersByFragment() {
            // Asserting on the exact sampler name would fail the day the model writes
            // "Login (POST)" instead, and a suite that fails on style gets rewritten to assert
            // nothing.
            assertThat(new StructuralExpectation.ExercisesSampler("login")
                    .isSatisfiedBy(PLAN, STRUCTURE)).isTrue();
            assertThat(new StructuralExpectation.ExercisesSampler("checkout")
                    .isSatisfiedBy(PLAN, STRUCTURE)).isFalse();
        }

        @Test
        @DisplayName("checks a variable was correlated, whatever case the model chose")
        void checksCorrelation() {
            assertThat(new StructuralExpectation.CorrelatesVariable("AUTH_TOKEN")
                    .isSatisfiedBy(PLAN, STRUCTURE)).isTrue();
            assertThat(new StructuralExpectation.CorrelatesVariable("session_id")
                    .isSatisfiedBy(PLAN, STRUCTURE)).isFalse();
        }

        @Test
        @DisplayName("reads the CSV header to check a column was parameterized")
        void checksCsvColumns() {
            assertThat(new StructuralExpectation.ParameterizesColumn("password")
                    .isSatisfiedBy(PLAN, STRUCTURE)).isTrue();
            assertThat(new StructuralExpectation.ParameterizesColumn("email")
                    .isSatisfiedBy(PLAN, STRUCTURE)).isFalse();
        }

        @Test
        @DisplayName("treats a plan with no test data as parameterizing nothing")
        void emptyCsvParameterizesNothing() {
            JmeterGenerationResult noData =
                    new JmeterGenerationResult("<plan/>", "", List.of(), "");

            assertThat(new StructuralExpectation.ParameterizesColumn("user")
                    .isSatisfiedBy(noData, STRUCTURE)).isFalse();
        }

        @Test
        @DisplayName("checks the plan covers enough of the capture")
        void checksCoverage() {
            assertThat(new StructuralExpectation.CoversAtLeast(2)
                    .isSatisfiedBy(PLAN, STRUCTURE)).isTrue();
            assertThat(new StructuralExpectation.CoversAtLeast(5)
                    .isSatisfiedBy(PLAN, STRUCTURE)).isFalse();
        }

        @Test
        @DisplayName("catches the plan that runs but sends a literal dollar-brace reference")
        void catchesUnresolvedReferences() {
            JmxStructure missingCorrelation = new JmxStructure(
                    List.of("login"), List.of("user"), List.of("user", "auth_token"));

            assertThat(new StructuralExpectation.ResolvesEveryVariable()
                    .isSatisfiedBy(PLAN, STRUCTURE)).isTrue();
            assertThat(new StructuralExpectation.ResolvesEveryVariable()
                    .isSatisfiedBy(PLAN, missingCorrelation)).isFalse();
        }

        @Test
        @DisplayName("describes itself in the words a failure report should use")
        void describesItself() {
            assertThat(new StructuralExpectation.ExercisesSampler("login").describe())
                    .isEqualTo("exercises a sampler named like 'login'");
            assertThat(new StructuralExpectation.CorrelatesVariable("auth_token").describe())
                    .isEqualTo("correlates the variable 'auth_token'");
            assertThat(new StructuralExpectation.ParameterizesColumn("user").describe())
                    .isEqualTo("parameterizes from the CSV column 'user'");
            assertThat(new StructuralExpectation.CoversAtLeast(3).describe())
                    .isEqualTo("covers at least 3 sampler(s)");
            assertThat(new StructuralExpectation.ResolvesEveryVariable().describe())
                    .isEqualTo("leaves no variable reference unresolved");
        }
    }

    @Nested
    @DisplayName("a case in the corpus")
    class Cases {

        @Test
        @DisplayName("refuses a case with no name to report it under")
        void requiresAName() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new EvaluationCase(
                            "  ", ExecutionMode.API, Path.of("a.har"), null, List.of()))
                    .withMessageContaining("must be named");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new EvaluationCase(
                            null, ExecutionMode.API, Path.of("a.har"), null, List.of()))
                    .withMessageContaining("must be named");
        }

        @Test
        @DisplayName("treats a case with no expectations as asserting nothing")
        void toleratesNoExpectations() {
            EvaluationCase bare = new EvaluationCase(
                    "bare", ExecutionMode.API, Path.of("a.har"), null, null);

            assertThat(bare.expectations()).isEmpty();
            assertThat(bare.describe()).isEqualTo("bare (API from a.har): 0 expectation(s)");
        }

        @Test
        @DisplayName("refuses a suite with nothing in it to measure")
        void suiteNeedsCases() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new EvaluationSuite("v1", List.of()))
                    .withMessageContaining("at least one case");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new EvaluationSuite("v1", null))
                    .withMessageContaining("at least one case");
        }

        @Test
        @DisplayName("labels an unlabelled suite so its scores are still filed somewhere")
        void unlabelledSuitesGetALabel() {
            EvaluationCase only = new EvaluationCase(
                    "one", ExecutionMode.API, Path.of("a.har"), null, List.of());

            assertThat(new EvaluationSuite("  ", List.of(only)).revision())
                    .isEqualTo("unlabelled revision");
            assertThat(new EvaluationSuite(null, List.of(only)).revision())
                    .isEqualTo("unlabelled revision");
        }
    }

    @Nested
    @DisplayName("one case's result")
    class Results {

        @Test
        @DisplayName("counts a plan that passed with no repair as a first-attempt success")
        void firstAttemptSuccess() {
            assertThat(passed("a", 1, 100).firstAttemptSuccess()).isTrue();
            assertThat(passed("a", 2, 100).firstAttemptSuccess()).isFalse();
        }

        @Test
        @DisplayName("counts repairs rather than attempts, so a clean run is zero")
        void countsHealCycles() {
            assertThat(passed("a", 1, 0).healCycles()).isZero();
            assertThat(passed("a", 3, 0).healCycles()).isEqualTo(2);
            assertThat(CaseResult.failed("a", 0, 0, "boom").healCycles()).isZero();
        }

        @Test
        @DisplayName("separates a plan that passed from one that also tested the right thing")
        void correctnessIsSeparateFromCompletion() {
            CaseResult wrongButPassing = new CaseResult(
                    "a", true, 1, 0, 1, List.of("correlates the variable 'auth_token'"), "");

            assertThat(wrongButPassing.completed()).isTrue();
            assertThat(wrongButPassing.firstAttemptSuccess()).isTrue();
            assertThat(wrongButPassing.fullyCorrect()).isFalse();
            assertThat(wrongButPassing.describe())
                    .contains("INCORRECT")
                    .contains("auth_token");
        }

        @Test
        @DisplayName("reports a case that never produced a plan, with why")
        void describesAFailure() {
            assertThat(CaseResult.failed("a", 3, 0, "budget exhausted").describe())
                    .isEqualTo("a: FAILED after 3 attempt(s) — budget exhausted");
            assertThat(CaseResult.failed("a", 3, 0, "budget exhausted").fullyCorrect()).isFalse();
        }

        @Test
        @DisplayName("normalizes a result the caller left half-filled")
        void normalizesSparseResults() {
            CaseResult sparse = new CaseResult("a", true, 1, 0, 0, null, null);

            assertThat(sparse.unmet()).isEmpty();
            assertThat(sparse.failure()).isEmpty();
            assertThat(sparse.describe()).contains("PASSED");
        }
    }

    @Nested
    @DisplayName("the corpus score")
    class Scoring {

        private EvaluationScore score() {
            return new EvaluationScore("prompts@v3", List.of(
                    passed("clean", 1, 1000),
                    passed("healed", 3, 5000),
                    new CaseResult("wrong", true, 1, 2000, 1, List.of("missed auth_token"), ""),
                    CaseResult.failed("broken", 3, 0, "budget exhausted")));
        }

        @Test
        @DisplayName("reports the share of cases the model got right with no repair at all")
        void reportsFirstAttemptSuccessRate() {
            assertThat(score().firstAttemptSuccessRate()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("separates completing from being correct")
        void separatesCompletionFromCorrectness() {
            assertThat(score().completionRate()).isEqualTo(0.75);
            assertThat(score().correctnessRate()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("averages the repairs and the tokens each case cost")
        void averagesCostAndHealing() {
            assertThat(score().meanHealCycles()).isEqualTo(1.0);
            assertThat(score().meanTokenCost()).isEqualTo(2000.0);
        }

        @Test
        @DisplayName("scores an empty corpus at zero rather than dividing by it")
        void emptyCorpusScoresZero() {
            EvaluationScore empty = new EvaluationScore("v1", List.of());

            assertThat(empty.caseCount()).isZero();
            assertThat(empty.firstAttemptSuccessRate()).isZero();
            assertThat(empty.completionRate()).isZero();
            assertThat(empty.correctnessRate()).isZero();
            assertThat(empty.meanHealCycles()).isZero();
            assertThat(empty.meanTokenCost()).isZero();
        }

        @Test
        @DisplayName("names every metric a revision made worse")
        void namesRegressions() {
            // The point of the harness: a prompt edit that quietly halves first-attempt success
            // has to show up as a number that moved, because nothing else will catch it.
            EvaluationScore baseline = new EvaluationScore("prompts@v2", List.of(
                    passed("clean", 1, 500),
                    passed("healed", 1, 500),
                    passed("wrong", 1, 500),
                    passed("broken", 1, 500)));

            assertThat(score().regressionsAgainst(baseline))
                    .anySatisfy(finding -> assertThat(finding)
                            .startsWith("first-attempt success got worse: 0.50, was 1.00"))
                    .anySatisfy(finding -> assertThat(finding).startsWith("correctness got worse"))
                    .anySatisfy(finding -> assertThat(finding).startsWith("completion got worse"))
                    .anySatisfy(finding -> assertThat(finding).startsWith("mean heal cycles got worse"))
                    .anySatisfy(finding -> assertThat(finding).startsWith("mean token cost got worse"));
        }

        @Test
        @DisplayName("says nothing when a revision improved on its baseline")
        void improvementsAreNotRegressions() {
            EvaluationScore weaker = new EvaluationScore("prompts@v2", List.of(
                    CaseResult.failed("clean", 3, 9000, "budget exhausted"),
                    CaseResult.failed("healed", 3, 9000, "budget exhausted"),
                    CaseResult.failed("wrong", 3, 9000, "budget exhausted"),
                    CaseResult.failed("broken", 3, 9000, "budget exhausted")));

            assertThat(score().regressionsAgainst(weaker)).isEmpty();
        }

        @Test
        @DisplayName("says nothing when a revision changed nothing")
        void identicalScoresDoNotRegress() {
            assertThat(score().regressionsAgainst(score())).isEmpty();
        }

        @Test
        @DisplayName("prints the aggregate above the per-case detail")
        void describesItself() {
            String report = score().describe();

            assertThat(report)
                    .contains("Evaluation of prompts@v3 over 4 case(s)")
                    .contains("First-attempt success : 50%")
                    .contains("Fully correct         : 50%")
                    .contains("Mean heal cycles      : 1.00")
                    .contains("broken: FAILED after 3 attempt(s)");
            assertThat(report.indexOf("First-attempt success"))
                    .isLessThan(report.indexOf("broken: FAILED"));
        }

        @Test
        @DisplayName("labels an unlabelled score rather than printing a blank")
        void toleratesNoRevisionLabel() {
            assertThat(new EvaluationScore(null, List.of()).revision()).isEmpty();
        }
    }
}
