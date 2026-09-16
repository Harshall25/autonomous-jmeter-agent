package com.ai.jmeter.agent.config;

import com.ai.jmeter.agent.domain.ci.GatePolicy;
import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The pipeline's performance policy, bound from {@code agent.gate.*}.
 *
 * <p>Kept apart from {@link AgentProperties} because it answers a different question. Those
 * settings say how the agent should build and run a test; these say what a team is willing to
 * merge, which is owned by whoever owns the pipeline and changes on a different schedule.
 *
 * @param enabled               judge the run against this policy at all; off by default, so
 *                              adding the agent to a pipeline never silently starts failing builds
 * @param failOnRegression      block when a sampler is materially and unusually slower than its
 *                              own baseline
 * @param failOnHealing         block when the agent had to repair the plan to make it pass
 * @param maxP95Millis          absolute ceiling on any sampler's p95; zero disables it
 * @param maxFailureRatePercent ceiling on the share of samples allowed to fail
 * @param reportFile            where the Markdown report is written for a pipeline step to post
 * @param stepSummaryFile       GitHub Actions' {@code $GITHUB_STEP_SUMMARY}, when running there
 */
@ConfigurationProperties(prefix = "agent.gate")
public record GateProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("true") boolean failOnRegression,
        @DefaultValue("false") boolean failOnHealing,
        @DefaultValue("0") long maxP95Millis,
        @DefaultValue("0") int maxFailureRatePercent,
        @DefaultValue("workspace/performance-report.md") Path reportFile,
        Path stepSummaryFile) {

    /** @return the policy the domain judges a run against. */
    public GatePolicy toPolicy() {
        return new GatePolicy(
                failOnRegression, failOnHealing, maxP95Millis, maxFailureRatePercent);
    }
}
