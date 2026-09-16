package com.ai.jmeter.agent.domain.analysis;

import java.util.List;
import java.util.stream.Collectors;

/**
 * One candidate explanation for why a run was slow, with the evidence behind it.
 *
 * <p>Deliberately a hypothesis rather than a conclusion. A model reasoning over latency,
 * regression history and spans is inferring from correlation, and presenting that as a finding
 * invites an engineer to act on a coincidence. Ranked hypotheses with their evidence attached
 * let the engineer do the last step — checking — which is the step they are actually good at.
 *
 * @param summary          the proposed cause, in one line
 * @param evidence         what in the run points at it
 * @param suggestedAction  what to do to confirm or fix it
 * @param confidence       0-100, the model's own estimate
 */
public record RootCauseHypothesis(
        String summary, String evidence, String suggestedAction, int confidence) {

    public RootCauseHypothesis {
        summary = summary == null ? "" : summary;
        evidence = evidence == null ? "" : evidence;
        suggestedAction = suggestedAction == null ? "" : suggestedAction;
        confidence = Math.clamp(confidence, 0, 100);
    }

    public String describe() {
        return """
                [%d%% confidence] %s
                  Evidence : %s
                  Next step: %s""".formatted(confidence, summary, evidence, suggestedAction);
    }

    /** @return the hypotheses as a ranked report, most confident first. */
    public static String report(List<RootCauseHypothesis> hypotheses) {
        if (hypotheses.isEmpty()) {
            return "No root-cause hypotheses were produced.";
        }
        return hypotheses.stream()
                .sorted((left, right) -> Integer.compare(right.confidence(), left.confidence()))
                .map(RootCauseHypothesis::describe)
                .collect(Collectors.joining("\n\n"));
    }
}
