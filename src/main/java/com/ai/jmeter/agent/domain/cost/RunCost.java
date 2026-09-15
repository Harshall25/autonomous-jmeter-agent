package com.ai.jmeter.agent.domain.cost;

import java.util.EnumMap;
import java.util.Map;

/**
 * What one agentic run spent, broken down by the kind of turn that spent it.
 *
 * @param byTurn      usage per turn kind
 * @param tokenBudget the ceiling this run was held to, or zero when unlimited
 */
public record RunCost(Map<AgentTurn, TokenUsage> byTurn, long tokenBudget) {

    public RunCost {
        byTurn = Map.copyOf(byTurn);
    }

    public static RunCost empty(long tokenBudget) {
        return new RunCost(new EnumMap<>(AgentTurn.class), tokenBudget);
    }

    public long totalTokens() {
        return byTurn.values().stream().mapToLong(TokenUsage::total).sum();
    }

    /** @return usage after adding one turn's consumption. */
    public RunCost plus(AgentTurn turn, TokenUsage usage) {
        // Constructed from the key type rather than copied from byTurn: EnumMap's copy
        // constructor rejects an empty non-EnumMap, which is exactly what the compact
        // constructor's Map.copyOf leaves behind on the first turn of a run.
        Map<AgentTurn, TokenUsage> updated = new EnumMap<>(AgentTurn.class);
        updated.putAll(byTurn);
        updated.merge(turn, usage, TokenUsage::plus);
        return new RunCost(updated, tokenBudget);
    }

    public boolean withinBudget() {
        return tokenBudget <= 0 || totalTokens() <= tokenBudget;
    }

    /** @return an operator-facing summary, and the basis of per-tenant chargeback. */
    public String describe() {
        return "%d token(s) across %s%s".formatted(
                totalTokens(),
                byTurn,
                tokenBudget > 0 ? " (budget %d)".formatted(tokenBudget) : " (no budget)");
    }
}
