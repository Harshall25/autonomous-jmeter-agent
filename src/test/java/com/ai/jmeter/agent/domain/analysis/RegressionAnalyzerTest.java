package com.ai.jmeter.agent.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("RegressionAnalyzer")
class RegressionAnalyzerTest {

    private final RegressionAnalyzer analyzer = new RegressionAnalyzer(5, 3.0, 1.10);

    private static RunSummary run(String label, long p95) {
        return new RunSummary(
                "run-" + p95, "fingerprint", Instant.EPOCH,
                Map.of(label, new SampleStatistics(label, 100, 0, p95, p95, p95, p95, p95)),
                10, 50);
    }

    /** A history whose p95 sits at {@code centre} with the given run-to-run swing. */
    private static List<RunSummary> history(String label, long centre, long swing, int runs) {
        return IntStream.range(0, runs)
                .mapToObj(index -> run(label, centre + (index % 2 == 0 ? swing : -swing)))
                .toList();
    }

    @Test
    @DisplayName("establishes a baseline rather than judging the first run")
    void firstRunEstablishesBaseline() {
        List<RegressionVerdict> verdicts = analyzer.analyze(run("login", 100), List.of());

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.regressed()).isFalse();
            assertThat(verdict.reason()).contains("establishes one");
            assertThat(verdict.describe()).contains("p95 100ms");
        });
    }

    @Test
    @DisplayName("withholds judgement until there is enough history to trust")
    void withholdsJudgementWithThinHistory() {
        List<RegressionVerdict> verdicts =
                analyzer.analyze(run("login", 500), history("login", 100, 2, 3));

        assertThat(verdicts).singleElement()
                .satisfies(verdict -> assertThat(verdict.regressed()).isFalse());
    }

    @Test
    @DisplayName("flags a slowdown that is both unusual and material")
    void flagsRealRegression() {
        List<RegressionVerdict> verdicts =
                analyzer.analyze(run("login", 400), history("login", 100, 2, 8));

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.regressed()).isTrue();
            assertThat(verdict.baselineP95Millis()).isEqualTo(100);
            assertThat(verdict.changeRatio()).isEqualTo(4.0);
            assertThat(verdict.reason()).contains("outside normal variance");
        });
    }

    @Test
    @DisplayName("stays quiet about a large move on a sampler that always swings that much")
    void toleratesNoisySamplers() {
        // A fixed 20% threshold would fire constantly here. Judging against the sampler's own
        // spread is what stops the check being dismissed as noise.
        List<RegressionVerdict> verdicts =
                analyzer.analyze(run("flaky", 150), history("flaky", 100, 60, 8));

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.regressed()).isFalse();
            assertThat(verdict.reason()).contains("normal run-to-run swing");
        });
    }

    @Test
    @DisplayName("stays quiet about a statistically odd but operationally trivial move")
    void ignoresTinyAbsoluteChanges() {
        // A 10ms move on a rock-steady 1000ms report is five deviations out and a 1% change.
        // Statistically striking, and nobody's problem; failing a build on it teaches engineers
        // to ignore the check.
        List<RegressionVerdict> verdicts =
                analyzer.analyze(run("report", 1010), history("report", 1000, 0, 8));

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.regressed()).isFalse();
            assertThat(verdict.reason()).contains("too small a change to act on");
        });
    }

    @Test
    @DisplayName("is not thrown off by a single outlier in the history")
    void resistsOutliers() {
        // A mean-based baseline lets one noisy-neighbour run poison comparisons for days.
        List<RunSummary> historyWithSpike = new java.util.ArrayList<>(history("login", 100, 2, 8));
        historyWithSpike.add(run("login", 100_000));

        List<RegressionVerdict> verdicts = analyzer.analyze(run("login", 105), historyWithSpike);

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.baselineP95Millis()).isBetween(98L, 102L);
            assertThat(verdict.regressed()).isFalse();
        });
    }

    @Test
    @DisplayName("says a run is consistent when it is")
    void reportsStability() {
        List<RegressionVerdict> verdicts =
                analyzer.analyze(run("login", 100), history("login", 100, 2, 8));

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.regressed()).isFalse();
            assertThat(verdict.reason()).contains("consistent with");
            assertThat(verdict.describe()).contains("within normal variance");
        });
    }

    @Test
    @DisplayName("ignores history from a sampler the current run no longer has")
    void ignoresDepartedSamplers() {
        List<RegressionVerdict> verdicts =
                analyzer.analyze(run("login", 100), history("checkout", 100, 2, 8));

        assertThat(verdicts).singleElement()
                .satisfies(verdict -> assertThat(verdict.label()).isEqualTo("login"));
    }

    @Test
    @DisplayName("ranks the worst regression first")
    void ranksWorstFirst() {
        RunSummary current = new RunSummary(
                "run", "fingerprint", Instant.EPOCH,
                Map.of(
                        "mild", new SampleStatistics("mild", 10, 0, 120, 120, 120, 120, 120),
                        "severe", new SampleStatistics("severe", 10, 0, 900, 900, 900, 900, 900)),
                10, 50);
        List<RunSummary> past = IntStream.range(0, 8)
                .mapToObj(index -> new RunSummary(
                        "past-" + index, "fingerprint", Instant.EPOCH,
                        Map.of(
                                "mild", new SampleStatistics("mild", 10, 0, 100, 100, 100, 100, 100),
                                "severe", new SampleStatistics("severe", 10, 0, 100, 100, 100, 100, 100)),
                        10, 50))
                .toList();

        assertThat(analyzer.analyze(current, past))
                .extracting(RegressionVerdict::label)
                .containsExactly("severe", "mild");
    }

    @Test
    @DisplayName("summarizes verdicts for a pull request comment")
    void rendersReport() {
        List<RegressionVerdict> regressed =
                analyzer.analyze(run("login", 400), history("login", 100, 2, 8));
        List<RegressionVerdict> clean =
                analyzer.analyze(run("login", 100), history("login", 100, 2, 8));

        assertThat(RegressionVerdict.report(regressed))
                .contains("1 performance regression(s) detected")
                .contains("REGRESSED");
        assertThat(RegressionVerdict.report(clean)).contains("No performance regressions detected");
    }

    @Test
    @DisplayName("reports no change ratio when there is no baseline to compare against")
    void noBaselineHasNoRatio() {
        assertThat(RegressionVerdict.noBaseline("login", 100).changeRatio()).isZero();
    }

    @Test
    @DisplayName("copes with a sampler whose history is all zeros")
    void handlesZeroBaseline() {
        // A sampler JMeter never timed still has to be judged without dividing by zero.
        List<RegressionVerdict> verdicts =
                analyzer.analyze(run("instant", 50), history("instant", 0, 0, 8));

        assertThat(verdicts).singleElement().satisfies(verdict -> {
            assertThat(verdict.baselineP95Millis()).isZero();
            assertThat(verdict.changeRatio()).isZero();
        });
    }
}
