package com.ai.jmeter.agent.domain.journal;

import java.util.List;

/**
 * One turn of the self-healing loop, recorded so a human can audit it afterwards.
 *
 * <p>Holds the three things a reviewer needs and the loop would otherwise throw away: what went
 * wrong, what the model said it was doing about it, and what actually changed in the plan. The
 * model's stated rationale is kept next to the diff on purpose — a rationale that does not match
 * the diff is the failure mode worth catching, and it is invisible if only one of the two is
 * retained.
 *
 * @param attempt          the attempt number this repair followed, so turns read in run order
 * @param failureSignature the grouped failure this repair was answering
 * @param diagnosis        the model's own account of what it believed was wrong
 * @param edits            the structural mutations it applied, described in plain language;
 *                         empty for a full rewrite, which has no discrete edits
 * @param fullRewrite      {@code true} when the model replaced the plan rather than patching it
 * @param diff             what actually changed between the two revisions
 */
public record HealTurn(
        int attempt,
        String failureSignature,
        String diagnosis,
        List<String> edits,
        boolean fullRewrite,
        PlanDiff diff) {

    public HealTurn {
        failureSignature = failureSignature == null ? "" : failureSignature;
        diagnosis = diagnosis == null ? "" : diagnosis;
        edits = edits == null ? List.of() : List.copyOf(edits);
    }

    /** @return how the repair was carried out, for a reader scanning a list of turns. */
    public String strategy() {
        return fullRewrite ? "full rewrite" : edits.size() + " structural edit(s)";
    }

    public String describe() {
        return """
                Turn after attempt %d (%s, %s)
                  Failure   : %s
                  Diagnosis : %s
                  Edits     : %s""".formatted(
                attempt, strategy(), diff.summarize(),
                failureSignature, diagnosis,
                edits.isEmpty() ? "none" : String.join("; ", edits));
    }
}
