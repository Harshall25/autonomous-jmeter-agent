package com.ai.jmeter.agent.domain.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.domain.analysis.RegressionVerdict;
import com.ai.jmeter.agent.domain.analysis.RootCauseHypothesis;
import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.journal.HealJournal;
import com.ai.jmeter.agent.domain.journal.HealTurn;
import com.ai.jmeter.agent.domain.journal.PlanDiff;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("The CI performance gate")
class PerformanceGateTest {

    private static final SampleStatistics CHECKOUT =
            new SampleStatistics("checkout", 100, 0, 420.0, 400, 900, 1200, 2000);
    private static final SampleStatistics SEARCH =
            new SampleStatistics("search", 100, 0, 40.0, 38, 60, 90, 120);

    private static RunSummary summary(SampleStatistics... statistics) {
        Map<String, SampleStatistics> byLabel = new LinkedHashMap<>();
        for (SampleStatistics entry : statistics) {
            byLabel.put(entry.label(), entry);
        }
        return new RunSummary("run-1", "plan-a", Instant.EPOCH, byLabel, 10, 40);
    }

    private static AgentRunOutcome outcome(RunAnalysis analysis, int attempts, HealJournal journal) {
        return new AgentRunOutcome(
                ExecutionMode.API,
                new JmeterGenerationResult("<plan/>", "user", List.of("user"), "rationale"),
                new WorkspaceArtifacts(Path.of("auto_test.jmx"), Path.of("test_data.csv")),
                ExecutionReport.success(100, ""),
                attempts,
                RedactionResult.clean("[]"),
                RunCost.empty(0),
                analysis,
                journal);
    }

    private static AgentRunOutcome healthyRun() {
        return outcome(new RunAnalysis(summary(CHECKOUT, SEARCH), List.of(
                new RegressionVerdict("checkout", 880, 900, 0.4, false, "within normal variance"),
                new RegressionVerdict("search", 58, 60, 0.2, false, "within normal variance"))),
                1, HealJournal.empty());
    }

    private static AgentRunOutcome regressedRun() {
        return outcome(new RunAnalysis(summary(CHECKOUT, SEARCH), List.of(
                new RegressionVerdict("checkout", 300, 900, 9.0, true, "outside normal variance"),
                new RegressionVerdict("search", 58, 60, 0.2, false, "within normal variance"))),
                1, HealJournal.empty());
    }

    private static HealJournal oneHealTurn() {
        return HealJournal.empty().plus(new HealTurn(
                1, "401 Unauthorized", "The bearer token was never extracted",
                List.of("Add a JSONPath extractor for auth_token"), false,
                PlanDiff.between("<plan/>", "<plan>\n<extractor/>\n</plan>")));
    }

    @Nested
    @DisplayName("deciding whether to block")
    class Judging {

        @Test
        @DisplayName("lets a run through when nothing moved")
        void allowsAHealthyRun() {
            GateVerdict verdict = new PerformanceGate(GatePolicy.defaults()).judge(healthyRun());

            assertThat(verdict.passed()).isTrue();
            assertThat(verdict.exitCode()).isZero();
            assertThat(verdict.describe()).isEqualTo("Performance gate passed.");
        }

        @Test
        @DisplayName("blocks on a statistically significant regression, naming the sampler")
        void blocksOnRegression() {
            GateVerdict verdict = new PerformanceGate(GatePolicy.defaults()).judge(regressedRun());

            assertThat(verdict.passed()).isFalse();
            assertThat(verdict.exitCode()).isEqualTo(2);
            assertThat(verdict.reasons()).singleElement().asString()
                    .contains("checkout")
                    .contains("REGRESSED");
        }

        @Test
        @DisplayName("reports a regression without blocking when the team asked only to be told")
        void reportsWithoutBlocking() {
            GatePolicy reportOnly = new GatePolicy(false, false, 0, 0);

            assertThat(new PerformanceGate(reportOnly).judge(regressedRun()).passed()).isTrue();
        }

        @Test
        @DisplayName("blocks a healed plan when the team does not trust one to test their change")
        void blocksAHealedPlan() {
            GatePolicy strict = new GatePolicy(true, true, 0, 0);

            GateVerdict verdict = new PerformanceGate(strict)
                    .judge(outcome(healthyRun().analysis(), 2, oneHealTurn()));

            assertThat(verdict.reasons()).singleElement().asString()
                    .contains("needed 2 attempt(s)")
                    .contains("+3/-1 line(s)");
        }

        @Test
        @DisplayName("lets a first-time pass through even under the strictest healing policy")
        void firstTimePassSurvivesTheHealingPolicy() {
            GatePolicy strict = new GatePolicy(true, true, 0, 0);

            assertThat(new PerformanceGate(strict).judge(healthyRun()).passed()).isTrue();
        }

        @Test
        @DisplayName("blocks a sampler over an absolute latency ceiling")
        void blocksOverTheLatencyCeiling() {
            GatePolicy ceiling = new GatePolicy(true, false, 500, 0);

            GateVerdict verdict = new PerformanceGate(ceiling).judge(healthyRun());

            assertThat(verdict.reasons()).singleElement().asString()
                    .isEqualTo("checkout: p95 900ms exceeds the 500ms ceiling");
        }

        @Test
        @DisplayName("applies no ceiling when none was configured")
        void noCeilingByDefault() {
            assertThat(GatePolicy.defaults().hasLatencyCeiling()).isFalse();
            assertThat(new PerformanceGate(GatePolicy.defaults()).judge(healthyRun()).passed())
                    .isTrue();
        }

        @Test
        @DisplayName("blocks when more samples failed than the budget allows")
        void blocksOverTheFailureBudget() {
            SampleStatistics flaky =
                    new SampleStatistics("checkout", 100, 5, 420.0, 400, 900, 1200, 2000);
            AgentRunOutcome run = outcome(
                    RunAnalysis.withoutComparison(summary(flaky)), 1, HealJournal.empty());

            GateVerdict verdict = new PerformanceGate(GatePolicy.defaults()).judge(run);

            assertThat(verdict.reasons()).singleElement().asString()
                    .isEqualTo("5.0% of samples failed, over the 0% budget");
        }

        @Test
        @DisplayName("allows failures within an explicitly granted budget")
        void allowsFailuresWithinBudget() {
            SampleStatistics flaky =
                    new SampleStatistics("checkout", 100, 5, 420.0, 400, 900, 1200, 2000);
            AgentRunOutcome run = outcome(
                    RunAnalysis.withoutComparison(summary(flaky)), 1, HealJournal.empty());

            assertThat(new PerformanceGate(new GatePolicy(true, false, 0, 10)).judge(run).passed())
                    .isTrue();
        }

        @Test
        @DisplayName("does not divide by zero when a run measured nothing")
        void toleratesARunWithNoSamples() {
            AgentRunOutcome run = outcome(
                    RunAnalysis.withoutComparison(summary()), 1, HealJournal.empty());

            assertThat(new PerformanceGate(GatePolicy.defaults()).judge(run).passed()).isTrue();
        }
    }

    @Nested
    @DisplayName("the policy itself")
    class Policy {

        @Test
        @DisplayName("refuses a negative latency ceiling rather than silently ignoring it")
        void rejectsANegativeCeiling() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new GatePolicy(true, false, -1, 0))
                    .withMessageContaining("maxP95Millis");
        }

        @Test
        @DisplayName("clamps a failure budget outside the range a percentage can take")
        void clampsTheFailureBudget() {
            assertThat(new GatePolicy(true, false, 0, 500).maxFailureRatePercent()).isEqualTo(100);
            assertThat(new GatePolicy(true, false, 0, -5).maxFailureRatePercent()).isZero();
        }

        @Test
        @DisplayName("describes itself so a pipeline log says what was enforced")
        void describesItself() {
            assertThat(GatePolicy.defaults().describe())
                    .isEqualTo("Gate policy: regressions block, healed plans are allowed, "
                            + "p95 ceiling none, failure budget 0%");
            assertThat(new GatePolicy(false, true, 500, 2).describe())
                    .isEqualTo("Gate policy: regressions are reported only, healed plans block, "
                            + "p95 ceiling 500ms, failure budget 2%");
        }

        @Test
        @DisplayName("refuses a blocking verdict that does not say what it objected to")
        void blockingVerdictsMustExplainThemselves() {
            // A gate that blocks without a reason is the reason teams switch gates off.
            List<String> nothing = List.of();
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> GateVerdict.blocked(nothing))
                    .withMessageContaining("must say what it objected to");
        }
    }

    @Nested
    @DisplayName("the pull request comment")
    class Comment {

        private String commentFor(AgentRunOutcome run, GatePolicy policy) {
            PerformanceGate gate = new PerformanceGate(policy);
            return gate.comment(run, gate.judge(run));
        }

        @Test
        @DisplayName("leads with the verdict a reviewer needs to see first")
        void leadsWithTheVerdict() {
            assertThat(commentFor(healthyRun(), GatePolicy.defaults()))
                    .startsWith("### ✅ Performance gate passed");
            assertThat(commentFor(regressedRun(), GatePolicy.defaults()))
                    .startsWith("### ❌ Performance gate failed");
        }

        @Test
        @DisplayName("tabulates each sampler's percentiles against its baseline")
        void tabulatesPercentileDeltas() {
            String comment = commentFor(regressedRun(), GatePolicy.defaults());

            assertThat(comment)
                    .contains("| Sampler | p50 | p95 | p99 | baseline p95 | change |")
                    .contains("| checkout | 400ms | 900ms | 1200ms | 300ms | ⚠️ +200% |")
                    .contains("| search | 38ms | 60ms | 90ms | 58ms | +3% |");
        }

        @Test
        @DisplayName("puts the slowest sampler first, where tuning effort belongs")
        void ordersBySlowest() {
            String comment = commentFor(healthyRun(), GatePolicy.defaults());

            assertThat(comment.indexOf("| checkout")).isLessThan(comment.indexOf("| search"));
        }

        @Test
        @DisplayName("marks a sampler with no history rather than inventing a delta")
        void marksSamplersWithNoBaseline() {
            AgentRunOutcome firstEverRun = outcome(
                    new RunAnalysis(summary(SEARCH),
                            List.of(RegressionVerdict.noBaseline("search", 60))),
                    1, HealJournal.empty());

            assertThat(commentFor(firstEverRun, GatePolicy.defaults()))
                    .contains("| search | 38ms | 60ms | 90ms | — | new |");
        }

        @Test
        @DisplayName("marks a sampler the analysis never judged")
        void marksUnjudgedSamplers() {
            AgentRunOutcome unanalyzed = outcome(
                    RunAnalysis.withoutComparison(summary(SEARCH)), 1, HealJournal.empty());

            assertThat(commentFor(unanalyzed, GatePolicy.defaults()))
                    .contains("| search | 38ms | 60ms | 90ms | — | new |");
        }

        @Test
        @DisplayName("says why the build is blocked, under the table that shows it")
        void explainsABlock() {
            String comment = commentFor(regressedRun(), GatePolicy.defaults());

            assertThat(comment).contains("**Why this is blocked**");
            assertThat(comment.indexOf("| Sampler"))
                    .isLessThan(comment.indexOf("**Why this is blocked**"));
        }

        @Test
        @DisplayName("says nothing about blocking when the gate passed")
        void passingCommentsHaveNoBlockSection() {
            assertThat(commentFor(healthyRun(), GatePolicy.defaults()))
                    .doesNotContain("**Why this is blocked**");
        }

        @Test
        @DisplayName("carries the run's headline facts for attribution")
        void carriesTheHeadline() {
            assertThat(commentFor(healthyRun(), GatePolicy.defaults()))
                    .contains("`API` · 200 sample(s) · 1 attempt(s) · 0 token(s)");
        }

        @Test
        @DisplayName("folds the diagnosis away rather than burying the table under it")
        void foldsAwayHypotheses() {
            AgentRunOutcome diagnosed = outcome(
                    regressedRun().analysis().withHypotheses(List.of(new RootCauseHypothesis(
                            "Connection pool exhaustion", "p99 reached 1200ms", "check the pool",
                            80))),
                    1, HealJournal.empty());

            assertThat(commentFor(diagnosed, GatePolicy.defaults()))
                    .contains("<details><summary>Root-cause hypotheses</summary>")
                    .contains("Connection pool exhaustion");
        }

        @Test
        @DisplayName("shows what the agent changed, so a healed plan is reviewable in the PR")
        void foldsAwayHealDiffs() {
            AgentRunOutcome healed = outcome(healthyRun().analysis(), 2, oneHealTurn());

            assertThat(commentFor(healed, GatePolicy.defaults()))
                    .contains("<details><summary>The agent healed this plan "
                            + "(+3/-1 line(s) across 1 turn(s))</summary>")
                    .contains("# after attempt 1 — The bearer token was never extracted")
                    .contains("+ <extractor/>");
        }

        @Test
        @DisplayName("says nothing about healing for a plan that passed first time")
        void cleanRunsHaveNoDiffSection() {
            assertThat(commentFor(healthyRun(), GatePolicy.defaults()))
                    .doesNotContain("The agent healed this plan");
        }
    }
}
