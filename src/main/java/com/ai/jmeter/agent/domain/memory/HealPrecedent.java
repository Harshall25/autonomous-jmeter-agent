package com.ai.jmeter.agent.domain.memory;

import java.util.List;

/**
 * A record of how a failure was repaired, kept so the next run does not rediscover it.
 *
 * <p>Stores the edits as their rendered descriptions rather than as structured mutations. That is
 * deliberate: a precedent is guidance handed to the model, not a patch replayed behind its back.
 * The model still reasons about whether the precedent applies to the plan in front of it, which
 * keeps a stale or coincidental match from silently corrupting a working plan.
 *
 * @param signature   the fingerprint of the failure this repaired
 * @param diagnosis   what the model concluded at the time
 * @param appliedEdits the edits it made, as one line each
 * @param worked      whether the run that followed actually passed
 */
public record HealPrecedent(
        String signature, String diagnosis, List<String> appliedEdits, boolean worked) {

    public HealPrecedent {
        appliedEdits = appliedEdits == null ? List.of() : List.copyOf(appliedEdits);
        diagnosis = diagnosis == null ? "" : diagnosis;
    }

    /** @return a briefing line for the repair prompt. */
    public String describe() {
        return """
                Failure: %s
                Diagnosis: %s
                Edits that %s: %s""".formatted(
                signature,
                diagnosis,
                worked ? "fixed it" : "did NOT fix it",
                appliedEdits);
    }
}
