package com.ai.jmeter.agent.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RunAnalysis")
class RunAnalysisTest {

    private static RunSummary summary() {
        return new RunSummary("run-1", "plan-a", Instant.EPOCH,
                Map.of("login", new SampleStatistics("login", 10, 0, 100, 100, 100, 100, 100)),
                5, 20);
    }

    private static RegressionVerdict verdict(String label, boolean regressed) {
        return new RegressionVerdict(label, 100, 400, 9.0, regressed, "because");
    }

    @Test
    @DisplayName("a run recorded without comparison reports no regressions")
    void withoutComparison() {
        RunAnalysis analysis = RunAnalysis.withoutComparison(summary());

        assertThat(analysis.verdicts()).isEmpty();
        assertThat(analysis.regressions()).isEmpty();
        assertThat(analysis.hasRegressions()).isFalse();
    }

    @Test
    @DisplayName("surfaces only the verdicts that actually regressed")
    void filtersRegressions() {
        RunAnalysis analysis = new RunAnalysis(summary(), List.of(
                verdict("login", true), verdict("orders", false)));

        assertThat(analysis.hasRegressions()).isTrue();
        assertThat(analysis.regressions())
                .extracting(RegressionVerdict::label)
                .containsExactly("login");
    }

    @Test
    @DisplayName("reports a clean run as having nothing to block on")
    void cleanRunHasNoRegressions() {
        RunAnalysis analysis = new RunAnalysis(summary(), List.of(verdict("login", false)));

        assertThat(analysis.hasRegressions()).isFalse();
    }

    @Test
    @DisplayName("renders the run and its comparison together")
    void describesBoth() {
        RunAnalysis analysis = new RunAnalysis(summary(), List.of(verdict("login", true)));

        assertThat(analysis.describe())
                .contains("Run run-1")
                .contains("1 performance regression(s) detected");
    }

    @Test
    @DisplayName("carries diagnosis alongside the comparison once it has been made")
    void attachesHypotheses() {
        RunAnalysis diagnosed = new RunAnalysis(summary(), List.of(verdict("login", true)))
                .withHypotheses(List.of(new RootCauseHypothesis(
                        "Connection pool exhaustion", "p99 diverged from p50", "check pool", 80)));

        assertThat(diagnosed.hypotheses()).hasSize(1);
        assertThat(diagnosed.verdicts()).hasSize(1);
        assertThat(diagnosed.describe())
                .contains("Root-cause hypotheses:")
                .contains("Connection pool exhaustion")
                .contains("[80% confidence]");
    }

    @Test
    @DisplayName("omits the diagnosis section when nothing was diagnosed")
    void omitsEmptyDiagnosis() {
        assertThat(new RunAnalysis(summary(), List.of(verdict("login", false))).describe())
                .doesNotContain("Root-cause hypotheses");
    }
}
