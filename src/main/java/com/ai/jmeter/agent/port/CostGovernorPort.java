package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.cost.AgentTurn;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.cost.TokenBudgetExceededException;
import com.ai.jmeter.agent.domain.cost.TokenUsage;

/**
 * Driven port: meters what the agent spends and stops it spending more than it was allotted.
 *
 * <p>FinOps teams do not block autonomous agents because they are expensive; they block them
 * because the cost has no ceiling and no owner. This port supplies both — a hard limit per run,
 * and a per-turn breakdown that can be attributed back to whoever asked for the run.
 */
public interface CostGovernorPort {

    /** Resets accounting for a new run. */
    void beginRun();

    /**
     * Books one turn's consumption against the current run's budget.
     *
     * @param turn  which kind of call was made
     * @param usage what it consumed
     * @throws TokenBudgetExceededException if this turn takes the run past its ceiling
     */
    void recordTurn(AgentTurn turn, TokenUsage usage);

    /** @return what the current run has spent so far. */
    RunCost currentCost();
}
