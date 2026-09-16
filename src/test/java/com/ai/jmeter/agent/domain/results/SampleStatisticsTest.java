package com.ai.jmeter.agent.domain.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SampleStatistics")
class SampleStatisticsTest {

    @Test
    @DisplayName("computes percentiles from raw elapsed times")
    void computesPercentiles() {
        List<Long> elapsed = LongStream.rangeClosed(1, 100).boxed().toList();

        SampleStatistics statistics = SampleStatistics.of("login", elapsed, 3);

        assertThat(statistics.count()).isEqualTo(100);
        assertThat(statistics.failures()).isEqualTo(3);
        assertThat(statistics.p50Millis()).isEqualTo(50);
        assertThat(statistics.p95Millis()).isEqualTo(95);
        assertThat(statistics.p99Millis()).isEqualTo(99);
        assertThat(statistics.maxMillis()).isEqualTo(100);
        assertThat(statistics.meanMillis()).isEqualTo(50.5);
    }

    @Test
    @DisplayName("reports a latency that was actually observed, never an interpolation")
    void percentilesAreObservedValues() {
        // A p99 quoted in a post-incident review has to be a real measurement, not a number
        // derived between two of them.
        SampleStatistics statistics =
                SampleStatistics.of("login", List.of(10L, 20L, 5000L), 0);

        assertThat(statistics.p99Millis()).isEqualTo(5000);
        assertThat(statistics.p50Millis()).isEqualTo(20);
    }

    @Test
    @DisplayName("does not let an average hide a tail")
    void averageDoesNotHideTheTail() {
        List<Long> mostlyFast = new java.util.ArrayList<>(
                LongStream.range(0, 99).map(index -> 10).boxed().toList());
        mostlyFast.add(4000L);

        SampleStatistics statistics = SampleStatistics.of("login", mostlyFast, 0);

        assertThat(statistics.meanMillis()).isLessThan(60);
        assertThat(statistics.maxMillis()).isEqualTo(4000);
    }

    @Test
    @DisplayName("handles a single sample")
    void singleSample() {
        SampleStatistics statistics = SampleStatistics.of("login", List.of(42L), 1);

        assertThat(statistics.p50Millis()).isEqualTo(42);
        assertThat(statistics.p99Millis()).isEqualTo(42);
        assertThat(statistics.failureRate()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("reports the share of samples that failed")
    void reportsFailureRate() {
        assertThat(SampleStatistics.of("login", List.of(1L, 2L, 3L, 4L), 1).failureRate())
                .isEqualTo(0.25);
    }

    @Test
    @DisplayName("refuses to summarize a sampler that recorded nothing")
    void refusesEmptySamples() {
        List<Long> nothing = List.of();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SampleStatistics.of("login", nothing, 0))
                .withMessageContaining("empty sample set for login");
    }

    @Test
    @DisplayName("renders a one-line summary")
    void describesItself() {
        assertThat(SampleStatistics.of("login", List.of(10L, 20L), 1).describe())
                .contains("login")
                .contains("n=2")
                .contains("p95=")
                .contains("failures=1");
    }
}
