package com.ai.jmeter.agent.domain.controlplane;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.journal.HealJournal;
import java.time.Instant;

/**
 * What the control plane keeps about one completed run.
 *
 * <p>Deliberately narrower than the outcome the agent hands back: the ledger is read by people
 * and by other systems, so it holds the account of the run — when, in what mode, how many turns,
 * what it cost, and every heal turn with its diff — rather than the artifacts themselves, which
 * stay on the workspace where a test runner expects to find them.
 *
 * @param runId           the run this entry accounts for
 * @param recordedAt      when the run finished
 * @param mode            the ingestion mode it was driven in
 * @param attempts        how many generate/execute/heal cycles it consumed
 * @param totalSamples    samples the passing run issued
 * @param planFingerprint groups this run with prior runs of the same plan
 * @param totalTokens     what the run spent with the model
 * @param rationale       the model's stated account of the plan that finally passed
 * @param journal         every heal turn, with the diff it produced
 */
public record RunLedgerEntry(
        String runId,
        Instant recordedAt,
        ExecutionMode mode,
        int attempts,
        long totalSamples,
        String planFingerprint,
        long totalTokens,
        String rationale,
        HealJournal journal) {

    public RunLedgerEntry {
        journal = journal == null ? HealJournal.empty() : journal;
        rationale = rationale == null ? "" : rationale;
    }

    /** @return {@code true} when the plan passed without any self-healing turn. */
    public boolean healedFirstTime() {
        return attempts == 1;
    }

    public String describe() {
        return "Run %s (%s) at %s: %d attempt(s), %d sample(s), %d token(s), %s".formatted(
                runId, mode, recordedAt, attempts, totalSamples, totalTokens, journal.churn());
    }
}
