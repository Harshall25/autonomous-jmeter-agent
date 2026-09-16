package com.ai.jmeter.agent.domain.analysis;

import java.util.List;

/**
 * A capacity answer derived from observations across several concurrency levels.
 *
 * <p>Fits the Universal Scalability Law, which describes throughput as limited by two distinct
 * effects: contention (serialized work, the {@code sigma} term) and coherency (the cost of keeping
 * participants consistent, the {@code kappa} term). The distinction is the whole point. A system
 * limited by contention plateaus and stays there, and buying hardware helps; a system limited by
 * coherency gets <em>slower</em> past its peak, and buying hardware makes it worse. Amdahl's law
 * cannot express the second case, and a plateau-only model would send an engineer to scale out a
 * system that will degrade when they do.
 *
 * @param contentionFactor the {@code sigma} term: the serialized share of work
 * @param coherencyFactor  the {@code kappa} term: the cost of cross-talk, zero when absent
 * @param baselineRate     throughput of a single worker
 * @param observations     the points the fit was made from
 */
public record SaturationModel(
        double contentionFactor,
        double coherencyFactor,
        double baselineRate,
        List<SaturationPoint> observations) {

    /** Fewer points than this and any curve fit is an interpolation dressed up as a model. */
    private static final int MINIMUM_POINTS = 3;

    public SaturationModel {
        observations = List.copyOf(observations);
    }

    /**
     * Fits a model to observations taken at increasing concurrency.
     *
     * @param observations at least three points at distinct concurrency levels
     * @return the fitted model
     * @throws IllegalArgumentException if there are too few points to fit anything meaningful
     */
    public static SaturationModel fit(List<SaturationPoint> observations) {
        if (observations.size() < MINIMUM_POINTS) {
            throw new IllegalArgumentException(
                    "Need at least %d observations to model saturation, got %d"
                            .formatted(MINIMUM_POINTS, observations.size()));
        }
        List<SaturationPoint> sorted = observations.stream()
                .sorted(java.util.Comparator.comparingInt(SaturationPoint::concurrency))
                .toList();

        SaturationPoint first = sorted.get(0);
        double baselineRate = first.efficiency();

        // Least-squares fit of the USL deficiency function, which linearizes the law:
        //   (N / X(N)) * X(1) - 1  =  sigma * (N - 1) + kappa * N * (N - 1)
        double sumXx = 0;
        double sumXy = 0;
        double sumZz = 0;
        double sumZy = 0;
        double sumXz = 0;

        for (SaturationPoint point : sorted) {
            double n = point.concurrency();
            if (n <= 1 || point.throughputPerSecond() <= 0) {
                continue;
            }
            double deficiency = (n * baselineRate / point.throughputPerSecond()) - 1;
            double x = n - 1;
            double z = n * (n - 1);

            sumXx += x * x;
            sumXy += x * deficiency;
            sumZz += z * z;
            sumZy += z * deficiency;
            sumXz += x * z;
        }

        double determinant = sumXx * sumZz - sumXz * sumXz;
        double contention;
        double coherency;
        if (Math.abs(determinant) < 1e-9) {
            // Collinear points: attribute everything to contention rather than inventing a
            // coherency term the data cannot distinguish.
            contention = sumXx == 0 ? 0 : sumXy / sumXx;
            coherency = 0;
        } else {
            contention = (sumXy * sumZz - sumZy * sumXz) / determinant;
            coherency = (sumZy * sumXx - sumXy * sumXz) / determinant;
        }

        return new SaturationModel(
                Math.max(0, contention), Math.max(0, coherency), baselineRate, sorted);
    }

    /**
     * @return the concurrency at which throughput peaks. Past this point a coherency-limited
     * system does not merely stop improving, it actively degrades.
     */
    public int peakConcurrency() {
        if (coherencyFactor <= 0) {
            // Contention-only: throughput approaches a ceiling but never turns down, so the
            // useful answer is the highest level actually observed rather than infinity.
            return observations.get(observations.size() - 1).concurrency();
        }
        return (int) Math.max(1, Math.floor(Math.sqrt((1 - contentionFactor) / coherencyFactor)));
    }

    /** @return predicted throughput at a given concurrency. */
    public double throughputAt(int concurrency) {
        double n = concurrency;
        return baselineRate * n
                / (1 + contentionFactor * (n - 1) + coherencyFactor * n * (n - 1));
    }

    public double maxSustainableThroughput() {
        return throughputAt(peakConcurrency());
    }

    /** @return {@code true} when adding load past the peak actively reduces throughput. */
    public boolean degradesUnderOverload() {
        return coherencyFactor > 0;
    }

    /**
     * @param plannedConcurrency the load the system is expected to carry
     * @return how much of the peak is still unused, negative when the plan exceeds capacity
     */
    public double headroomAt(int plannedConcurrency) {
        return 1 - ((double) plannedConcurrency / peakConcurrency());
    }

    /** @return the capacity answer, in the terms a capacity-planning conversation happens in. */
    public String describe() {
        return """
                Peak concurrency      : %d
                Max sustainable rate  : %.2f samples/second
                Contention (sigma)    : %.4f
                Coherency (kappa)     : %.4f
                Beyond the peak       : %s""".formatted(
                peakConcurrency(),
                maxSustainableThroughput(),
                contentionFactor,
                coherencyFactor,
                degradesUnderOverload()
                        ? "throughput DEGRADES; scaling out will make this worse"
                        : "throughput plateaus; scaling out should help");
    }
}
