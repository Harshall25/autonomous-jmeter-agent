package com.ai.jmeter.agent.domain.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("SaturationModel")
class SaturationModelTest {

    /**
     * Generates observations from a known Universal Scalability Law curve, so the fit can be
     * checked against the coefficients that produced the data rather than against a guess.
     */
    private static List<SaturationPoint> curve(
            double baselineRate, double contention, double coherency, int... concurrencies) {
        return java.util.Arrays.stream(concurrencies)
                .mapToObj(n -> {
                    double throughput = baselineRate * n
                            / (1 + contention * (n - 1) + coherency * n * (n - 1));
                    return new SaturationPoint(n, throughput, (long) (1000 * n / throughput));
                })
                .toList();
    }

    @Test
    @DisplayName("recovers the coefficients of a contention-limited system")
    void fitsContentionOnlySystem() {
        SaturationModel model = SaturationModel.fit(curve(100, 0.05, 0.0, 1, 2, 4, 8, 16, 32));

        assertThat(model.contentionFactor()).isCloseTo(0.05, within());
        assertThat(model.coherencyFactor()).isCloseTo(0.0, within());
        assertThat(model.baselineRate()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("recovers the coefficients of a coherency-limited system")
    void fitsCoherencyLimitedSystem() {
        SaturationModel model = SaturationModel.fit(curve(100, 0.02, 0.001, 1, 2, 4, 8, 16, 32, 64));

        assertThat(model.contentionFactor()).isCloseTo(0.02, within());
        assertThat(model.coherencyFactor()).isCloseTo(0.001, within());
    }

    @Test
    @DisplayName("finds the concurrency at which throughput peaks")
    void findsThePeak() {
        // With sigma=0.02 and kappa=0.001 the analytic peak is sqrt((1-0.02)/0.001) ~= 31.
        SaturationModel model = SaturationModel.fit(curve(100, 0.02, 0.001, 1, 2, 4, 8, 16, 32, 64));

        assertThat(model.peakConcurrency()).isBetween(29, 33);
    }

    @Test
    @DisplayName("warns that a coherency-limited system gets worse when scaled out")
    void warnsAboutRetrogradeScaling() {
        // The distinction Amdahl's law cannot express: past the peak this system does not
        // plateau, it actively degrades, and adding hardware makes it worse.
        SaturationModel model = SaturationModel.fit(curve(100, 0.02, 0.001, 1, 2, 4, 8, 16, 32, 64));

        assertThat(model.degradesUnderOverload()).isTrue();
        assertThat(model.describe())
                .contains("throughput DEGRADES")
                .contains("Peak concurrency")
                .contains("Max sustainable rate");
    }

    @Test
    @DisplayName("says a contention-limited system plateaus rather than degrading")
    void contentionLimitedSystemPlateaus() {
        SaturationModel model = SaturationModel.fit(curve(100, 0.05, 0.0, 1, 2, 4, 8, 16, 32));

        assertThat(model.degradesUnderOverload()).isFalse();
        assertThat(model.describe()).contains("throughput plateaus");
        assertThat(model.peakConcurrency())
                .as("without a turning point the honest answer is the highest level measured")
                .isEqualTo(32);
    }

    @Test
    @DisplayName("predicts throughput at a concurrency that was never measured")
    void predictsUnmeasuredConcurrency() {
        SaturationModel model = SaturationModel.fit(curve(100, 0.02, 0.001, 1, 2, 4, 8, 16, 32, 64));

        double predicted = model.throughputAt(24);
        double actual = 100 * 24.0 / (1 + 0.02 * 23 + 0.001 * 24 * 23);

        assertThat(predicted).isCloseTo(actual, org.assertj.core.data.Offset.offset(actual * 0.05));
    }

    @Test
    @DisplayName("reports remaining headroom against a planned load")
    void reportsHeadroom() {
        SaturationModel model = SaturationModel.fit(curve(100, 0.02, 0.001, 1, 2, 4, 8, 16, 32, 64));

        assertThat(model.headroomAt(model.peakConcurrency() / 2))
                .as("running at half the peak leaves roughly half the capacity unused")
                .isCloseTo(0.5, org.assertj.core.data.Offset.offset(0.1));
        assertThat(model.headroomAt(model.peakConcurrency() * 2))
                .as("planning past the peak is negative headroom, not a warning to ignore")
                .isNegative();
    }

    @Test
    @DisplayName("reports the maximum rate the system can actually sustain")
    void reportsMaxSustainableThroughput() {
        SaturationModel model = SaturationModel.fit(curve(100, 0.02, 0.001, 1, 2, 4, 8, 16, 32, 64));

        assertThat(model.maxSustainableThroughput())
                .isEqualTo(model.throughputAt(model.peakConcurrency()));
    }

    @Test
    @DisplayName("refuses to fit a curve to too few points")
    void refusesThinData() {
        List<SaturationPoint> twoPoints = curve(100, 0.02, 0.001, 1, 2);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> SaturationModel.fit(twoPoints))
                .withMessageContaining("at least 3 observations");
    }

    @Test
    @DisplayName("attributes everything to contention when the data cannot separate the terms")
    void collinearDataFallsBackToContention() {
        // Repeating one concurrency level gives a singular system; inventing a coherency term
        // from it would be fabricating a conclusion the measurements do not support.
        List<SaturationPoint> collinear = List.of(
                new SaturationPoint(1, 100, 10),
                new SaturationPoint(2, 190, 11),
                new SaturationPoint(2, 190, 11));

        SaturationModel model = SaturationModel.fit(collinear);

        assertThat(model.coherencyFactor()).isZero();
        assertThat(model.contentionFactor()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("reports no scaling limits when every observation is at one worker")
    void allPointsAtSingleWorker() {
        // Nothing here says anything about how the system scales, so the honest fit is a flat
        // one rather than coefficients invented from a single load level.
        List<SaturationPoint> singleLevel = List.of(
                new SaturationPoint(1, 100, 10),
                new SaturationPoint(1, 102, 10),
                new SaturationPoint(1, 98, 10));

        SaturationModel model = SaturationModel.fit(singleLevel);

        assertThat(model.contentionFactor()).isZero();
        assertThat(model.coherencyFactor()).isZero();
        assertThat(model.peakConcurrency()).isEqualTo(1);
    }

    @Test
    @DisplayName("ignores observations that measured nothing")
    void ignoresZeroThroughputPoints() {
        List<SaturationPoint> withDeadRun = List.of(
                new SaturationPoint(1, 100, 10),
                new SaturationPoint(2, 0, 0),
                new SaturationPoint(4, 350, 12),
                new SaturationPoint(8, 640, 13));

        assertThat(SaturationModel.fit(withDeadRun).contentionFactor()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("measures efficiency as throughput per unit of concurrency")
    void pointEfficiency() {
        assertThat(new SaturationPoint(4, 200, 20).efficiency()).isEqualTo(50.0);
        assertThat(new SaturationPoint(0, 200, 20).efficiency()).isZero();
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.005);
    }
}
