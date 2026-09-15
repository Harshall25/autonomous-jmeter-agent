package com.ai.jmeter.agent.domain.cost;

import java.io.Serial;

/**
 * Raised when a run consumes more tokens than it was allotted.
 *
 * <p>An unbounded self-healing loop is an unbounded spend loop: every failed attempt buys another
 * model call, and a plan the model cannot fix will keep buying them. The budget is what makes the
 * worst case a bounded, attributable cost rather than an open-ended one.
 */
public class TokenBudgetExceededException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final transient RunCost cost;

    public TokenBudgetExceededException(RunCost cost) {
        super("Token budget exhausted: %s. Raise agent.cost.token-budget, or lower "
                .formatted(cost.describe())
                + "agent.jmeter.max-retries so fewer repair turns are attempted.");
        this.cost = cost;
    }

    public RunCost cost() {
        return cost;
    }
}
