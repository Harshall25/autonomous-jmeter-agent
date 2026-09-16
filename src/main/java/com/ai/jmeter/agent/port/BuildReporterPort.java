package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.ci.GateVerdict;

/**
 * Driven port: publishes the gate's decision where the pipeline and its reviewers will see it.
 *
 * <p>Performance testing that lives outside the merge workflow is the first thing dropped under
 * deadline pressure. Putting the percentile table on the pull request is what turns it from an
 * occasional exercise into a standard nobody has to remember to apply.
 */
public interface BuildReporterPort {

    /**
     * @param verdict the decision reached about the run
     * @param comment the Markdown report, ready to post
     * @throws BuildReportException if the report cannot be published
     */
    void publish(GateVerdict verdict, String comment);
}
