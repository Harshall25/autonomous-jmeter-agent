package com.ai.jmeter.agent.domain.eval;

import java.util.List;

/**
 * A golden corpus and the label its scores are filed under.
 *
 * @param revision what is being evaluated — a prompt revision and the model it ran against, which
 *                 is what a score is only meaningful relative to
 * @param cases    the captures to run, in declaration order
 */
public record EvaluationSuite(String revision, List<EvaluationCase> cases) {

    public EvaluationSuite {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("An evaluation suite must declare at least one case");
        }
        revision = revision == null || revision.isBlank() ? "unlabelled revision" : revision;
        cases = List.copyOf(cases);
    }
}
