package com.ai.jmeter.agent.config;

import com.ai.jmeter.agent.domain.cost.AgentTurn;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Operator-facing configuration for the agent, bound from {@code agent.jmeter.*}.
 *
 * @param homePath                   JMeter's {@code bin} directory, holding the {@code jmeter}
 *                                   launcher
 * @param maxRetries                 total JMeter runs the agent may spend on one request,
 *                                   counting the first attempt. Three means one generation and
 *                                   up to two repairs.
 * @param workspace                  directory that generated plans, test data and results are
 *                                   written to
 * @param executionTimeout           how long a single JMeter run may take before it is killed
 * @param libPath                    JMeter's {@code lib} directory; derived from
 *                                   {@code homePath} when not set
 * @param maxHarEntries              cap on HAR requests forwarded to the model
 * @param maxHarBodyCharacters       cap on characters kept from any one request body
 * @param maxSqlQueries              cap on distinct SQL statements forwarded to the model
 * @param maxProcessOutputCharacters cap on JMeter console output retained for diagnostics
 * @param maxRecordedFailures        cap on failing samples retained from one results file
 * @param strictCompliance           when true, refuse any capture carrying regulated material
 *                                   rather than sending it to the model in substituted form
 * @param recalledPrecedents         how many past repairs to put in front of the model per turn
 * @param maxTopics                  cap on streaming topics forwarded to the model
 * @param workloadRampUpSeconds      ramp-up applied when a workload shape is inferred
 * @param historyDepth               how many past runs a regression baseline draws on
 * @param minimumBaselineRuns        runs needed before a baseline is trusted at all
 * @param regressionDeviationThreshold robust deviations above baseline that count as
 *                                   a regression
 * @param regressionMinimumChangeRatio floor on relative change, so a statistically
 *                                   significant but trivial move does not fail a build
 * @param traceCorrelation           emit a W3C traceparent on every request
 * @param tokenBudget                hard ceiling on tokens per run; zero means unlimited
 * @param models                     optional per-turn model overrides; unset turns use the
 *                                   client's configured default
 */
@ConfigurationProperties(prefix = "agent.jmeter")
public record AgentProperties(
        @DefaultValue("/opt/jmeter/bin") Path homePath,
        @DefaultValue("3") int maxRetries,
        @DefaultValue("workspace") Path workspace,
        @DefaultValue("10m") Duration executionTimeout,
        Path libPath,
        @DefaultValue("150") int maxHarEntries,
        @DefaultValue("2000") int maxHarBodyCharacters,
        @DefaultValue("200") int maxSqlQueries,
        @DefaultValue("8000") int maxProcessOutputCharacters,
        @DefaultValue("500") int maxRecordedFailures,
        @DefaultValue("false") boolean strictCompliance,
        @DefaultValue("3") int recalledPrecedents,
        @DefaultValue("50") int maxTopics,
        @DefaultValue("30") int workloadRampUpSeconds,
        @DefaultValue("10") int historyDepth,
        @DefaultValue("5") int minimumBaselineRuns,
        @DefaultValue("3.0") double regressionDeviationThreshold,
        @DefaultValue("1.10") double regressionMinimumChangeRatio,
        @DefaultValue("false") boolean traceCorrelation,
        @DefaultValue("0") long tokenBudget,
        Map<AgentTurn, String> models) {

    /**
     * Resolves where JDBC drivers are expected to live.
     *
     * <p>{@code homePath} points at JMeter's {@code bin} directory, so its sibling {@code lib} is
     * the conventional location. An explicit {@code libPath} overrides that for non-standard
     * layouts.
     *
     * @return the directory to scan for driver JARs
     */
    /** @return per-turn model routing, empty when the operator has not opted into it. */
    public Map<AgentTurn, String> modelsByTurn() {
        return models == null ? Map.of() : Map.copyOf(models);
    }

    public Path resolvedLibPath() {
        if (libPath != null) {
            return libPath;
        }
        Path jmeterRoot = homePath.getParent();
        return jmeterRoot == null ? homePath.resolve("lib") : jmeterRoot.resolve("lib");
    }
}
