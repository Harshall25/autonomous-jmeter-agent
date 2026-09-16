package com.ai.jmeter.agent.domain.eval;

import java.util.List;

/**
 * What the agent actually did with one case in the corpus.
 *
 * <p>A case that threw is recorded rather than propagated: one unrunnable capture must not cost
 * the scores of every case after it, and "this case now fails outright" is itself the most
 * important thing a prompt revision can tell you.
 *
 * @param caseName      the case this scores
 * @param completed     whether the agent produced a passing plan at all
 * @param attempts      generate/execute/heal cycles consumed; one means no healing was needed
 * @param tokens        what the case cost with the model
 * @param satisfied     expectations the produced plan met
 * @param unmet         expectations it did not, described for the report
 * @param failure       why the case did not complete; empty when it did
 */
public record CaseResult(
        String caseName,
        boolean completed,
        int attempts,
        long tokens,
        int satisfied,
        List<String> unmet,
        String failure) {

    public CaseResult {
        unmet = unmet == null ? List.of() : List.copyOf(unmet);
        failure = failure == null ? "" : failure;
    }

    /** @return the case as it is recorded when the agent could not produce a passing plan. */
    public static CaseResult failed(String caseName, int attempts, long tokens, String failure) {
        return new CaseResult(caseName, false, attempts, tokens, 0, List.of(), failure);
    }

    /** @return {@code true} when the model got the plan right without a single repair turn. */
    public boolean firstAttemptSuccess() {
        return completed && attempts == 1;
    }

    /** @return {@code true} when the plan passed and satisfied every expectation. */
    public boolean fullyCorrect() {
        return completed && unmet.isEmpty();
    }

    /** @return repairs spent, which is attempts less the one that was always going to happen. */
    public int healCycles() {
        return Math.max(0, attempts - 1);
    }

    public String describe() {
        if (!completed) {
            return "%s: FAILED after %d attempt(s) — %s".formatted(caseName, attempts, failure);
        }
        String verdict = unmet.isEmpty()
                ? "PASSED"
                : "INCORRECT (%s)".formatted(String.join("; ", unmet));
        return "%s: %s in %d attempt(s), %d expectation(s) met, %d token(s)".formatted(
                caseName, verdict, attempts, satisfied, tokens);
    }
}
