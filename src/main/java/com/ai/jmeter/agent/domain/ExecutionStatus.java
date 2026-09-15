package com.ai.jmeter.agent.domain;

/**
 * The outcome categories of a single JMeter run.
 *
 * <p>The distinction matters to the healing loop: a {@link #SAMPLE_FAILURE} means the plan
 * ran but the requests were rejected (bad correlation, missing header), whereas a
 * {@link #PROCESS_FAILURE} or {@link #NO_SAMPLES} usually means the plan itself is
 * structurally invalid. Both are healable, but they steer the model differently.
 */
public enum ExecutionStatus {

    /** Every sampler in the plan reported success. The agentic loop terminates. */
    SUCCESS,

    /** The plan executed but at least one sampler failed or returned an error status code. */
    SAMPLE_FAILURE,

    /** The JMeter CLI exited non-zero — typically malformed XML or a missing element. */
    PROCESS_FAILURE,

    /** JMeter exited cleanly but wrote no samples, so nothing was actually exercised. */
    NO_SAMPLES,

    /** Pre-flight validation rejected the plan, so no execution was attempted at all. */
    VALIDATION_FAILURE;

    public boolean successful() {
        return this == SUCCESS;
    }
}
