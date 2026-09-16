package com.ai.jmeter.agent.domain.governance;

/**
 * What a principal is allowed to do, named as the action rather than the endpoint.
 *
 * <p>Permissions rather than role checks at the call site: {@code if (role == ADMIN)} scattered
 * through adapters is how an authorization model ends up meaning something different in each
 * place it is enforced.
 */
public enum Permission {

    /** See that runs happened, and what they measured. */
    VIEW_RUNS,

    /** See the plan the agent produced and every heal turn it took to get there. */
    VIEW_HEAL_DIFFS,

    /** Start a run against a capture. */
    START_RUN,

    /** Attest that a generated plan is fit to run against a regulated system. */
    APPROVE_PLAN,

    /** Change who is in a tenant and what they may do. */
    MANAGE_TENANT
}
