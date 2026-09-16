package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.results.RunSummary;
import java.util.List;

/**
 * Driven port: durable history of what each run measured.
 *
 * <p>The precondition for every analytic capability above it. A results file that lives and dies
 * with one run can tell you whether that run passed; only a series can tell you whether the system
 * is getting slower, which is the question teams actually need answered.
 *
 * <p>Deliberately not the {@code .jtl} file. Parsing flat CSV is linear in sample count and
 * collapses at real throughput, and cross-run percentile queries over it are not practical. An
 * implementation is expected to store summaries in a form that can be queried by plan and time.
 */
public interface ResultStorePort {

    /**
     * Records one run's results.
     *
     * @param summary what the run measured
     * @throws ResultStoreException if the results cannot be persisted
     */
    void record(RunSummary summary);

    /**
     * Reads prior runs of the same plan.
     *
     * @param planFingerprint groups runs of the same test into a comparable series
     * @param limit           how many recent runs to return
     * @return prior runs, most recent first; empty when this plan has no history
     */
    List<RunSummary> history(String planFingerprint, int limit);
}
