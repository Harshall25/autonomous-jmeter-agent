package com.ai.jmeter.agent.adapter.api;

import com.ai.jmeter.agent.domain.journal.HealTurn;
import java.util.List;

/**
 * The wire shape of one self-healing turn, with the diff a reviewer reads.
 *
 * @param attempt          the attempt this repair followed
 * @param failureSignature the grouped failure the repair was answering
 * @param diagnosis        what the model said it believed was wrong
 * @param strategy         how it repaired: structural edits, or a full rewrite
 * @param edits            the structural mutations applied, in plain language
 * @param diff             the unified diff lines between the two plan revisions
 * @param addedLines       lines the newer revision introduced
 * @param removedLines     lines the older revision lost
 * @param truncated        whether the plans were too large to diff line by line
 */
public record HealTurnView(
        int attempt,
        String failureSignature,
        String diagnosis,
        String strategy,
        List<String> edits,
        List<String> diff,
        int addedLines,
        int removedLines,
        boolean truncated) {

    static HealTurnView of(HealTurn turn) {
        return new HealTurnView(
                turn.attempt(),
                turn.failureSignature(),
                turn.diagnosis(),
                turn.strategy(),
                turn.edits(),
                turn.diff().unifiedLines(),
                turn.diff().addedLines(),
                turn.diff().removedLines(),
                turn.diff().truncated());
    }
}
