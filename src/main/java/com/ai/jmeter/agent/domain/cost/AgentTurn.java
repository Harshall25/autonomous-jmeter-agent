package com.ai.jmeter.agent.domain.cost;

/**
 * The kinds of model call the agent makes, which differ enough in difficulty to be worth routing
 * and costing separately.
 */
public enum AgentTurn {

    /** Authoring a plan from observed traffic. Long output, moderate reasoning. */
    GENERATION,

    /** Diagnosing a failure and proposing edits. Short output, the hardest reasoning. */
    REPAIR,

    /** Regenerating a plan wholesale. The most expensive turn, and the one to avoid. */
    REWRITE
}
