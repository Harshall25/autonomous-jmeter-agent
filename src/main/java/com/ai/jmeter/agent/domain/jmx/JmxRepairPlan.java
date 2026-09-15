package com.ai.jmeter.agent.domain.jmx;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The model's proposed repair for a failing plan, expressed as edits rather than as a rewrite.
 *
 * @param mutations   the edits to apply, in order
 * @param diagnosis   what the model concluded went wrong
 * @param fullRewrite set when the model judges the plan beyond patching
 */
public record JmxRepairPlan(List<JmxMutation> mutations, String diagnosis, boolean fullRewrite) {

    public JmxRepairPlan {
        mutations = mutations == null ? List.of() : List.copyOf(mutations);
        diagnosis = diagnosis == null ? "" : diagnosis;
    }

    /** A repair the model declined to express as edits. */
    public static JmxRepairPlan rewrite(String diagnosis) {
        return new JmxRepairPlan(List.of(), diagnosis, true);
    }

    /**
     * @return {@code true} when the caller should fall back to regenerating the whole plan. An
     * empty mutation list counts: the model was asked for edits and produced none, so patching
     * would be a no-op and the loop would spin without progress.
     */
    public boolean requiresFullRewrite() {
        return fullRewrite || mutations.isEmpty();
    }

    /** @return a one-line-per-edit audit trail of what this repair changes. */
    public String describe() {
        return mutations.stream()
                .map(mutation -> "  - " + mutation.describe())
                .collect(Collectors.joining("\n"));
    }
}
