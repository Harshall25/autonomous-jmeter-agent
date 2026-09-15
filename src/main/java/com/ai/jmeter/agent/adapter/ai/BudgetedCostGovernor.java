package com.ai.jmeter.agent.adapter.ai;

import com.ai.jmeter.agent.domain.cost.AgentTurn;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.cost.TokenBudgetExceededException;
import com.ai.jmeter.agent.domain.cost.TokenUsage;
import com.ai.jmeter.agent.port.CostGovernorPort;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: in-memory token accounting with a hard per-run ceiling.
 *
 * <p>The budget is checked <em>after</em> a turn rather than before it, because the cost of a call
 * is not knowable until the provider reports it. That means a run can overshoot by at most one
 * turn — the alternative, refusing to start a turn that might overshoot, would strand runs that
 * had budget left for a cheap repair.
 *
 * <p>State is held for a single run at a time, which matches the CLI's one-run-per-process
 * lifecycle. A multi-tenant control plane would key this by run identifier instead; the port keeps
 * that swap local to this class.
 */
public final class BudgetedCostGovernor implements CostGovernorPort {

    private static final Logger log = LoggerFactory.getLogger(BudgetedCostGovernor.class);

    private final long tokenBudget;
    private final AtomicReference<RunCost> cost;

    public BudgetedCostGovernor(long tokenBudget) {
        this.tokenBudget = tokenBudget;
        this.cost = new AtomicReference<>(RunCost.empty(tokenBudget));
    }

    @Override
    public void beginRun() {
        cost.set(RunCost.empty(tokenBudget));
    }

    @Override
    public void recordTurn(AgentTurn turn, TokenUsage usage) {
        RunCost updated = cost.updateAndGet(current -> current.plus(turn, usage));
        log.debug("{} turn consumed {} token(s); run total {}",
                turn, usage.total(), updated.totalTokens());

        if (!updated.withinBudget()) {
            log.error("Token budget exhausted: {}", updated.describe());
            throw new TokenBudgetExceededException(updated);
        }
    }

    @Override
    public RunCost currentCost() {
        return cost.get();
    }
}
