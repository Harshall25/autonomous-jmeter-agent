package com.ai.jmeter.agent.adapter.api;

import com.ai.jmeter.agent.domain.controlplane.RunLedgerEntry;
import java.util.List;

/**
 * The wire shape of a run, as the control plane API serves it.
 *
 * <p>Kept in the adapter layer rather than serializing the domain record directly, so that the
 * published contract other systems build against can evolve independently of the model the agent
 * reasons with — and so a field added to the domain does not silently become public API.
 *
 * @param runId           the run this describes
 * @param recordedAt      ISO-8601 instant the run finished
 * @param mode            the ingestion mode it was driven in
 * @param attempts        generate/execute/heal cycles consumed
 * @param healedFirstTime whether the model got the plan right without any repair
 * @param totalSamples    samples the passing run issued
 * @param planFingerprint groups this run with prior runs of the same plan
 * @param totalTokens     tokens spent with the model
 * @param rationale       the model's account of the plan that finally passed
 * @param churn           one-line summary of how much the loop rewrote
 * @param heals           each heal turn, newest last; empty in the list view
 */
public record RunView(
        String runId,
        String recordedAt,
        String mode,
        int attempts,
        boolean healedFirstTime,
        long totalSamples,
        String planFingerprint,
        long totalTokens,
        String rationale,
        String churn,
        List<HealTurnView> heals) {

    /** @return the run without its heal turns, for a listing that only needs the headline. */
    static RunView summaryOf(RunLedgerEntry entry) {
        return of(entry, List.of());
    }

    /** @return the run with every heal turn and its diff, for the detail view. */
    static RunView detailOf(RunLedgerEntry entry) {
        return of(entry, entry.journal().turns().stream().map(HealTurnView::of).toList());
    }

    private static RunView of(RunLedgerEntry entry, List<HealTurnView> heals) {
        return new RunView(
                entry.runId(),
                entry.recordedAt().toString(),
                entry.mode().name(),
                entry.attempts(),
                entry.healedFirstTime(),
                entry.totalSamples(),
                entry.planFingerprint(),
                entry.totalTokens(),
                entry.rationale(),
                entry.journal().churn(),
                heals);
    }
}
