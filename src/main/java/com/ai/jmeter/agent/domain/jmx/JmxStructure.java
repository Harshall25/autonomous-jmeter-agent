package com.ai.jmeter.agent.domain.jmx;

import java.util.List;

/**
 * A structural summary of a test plan: what it exercises, and how its variables flow.
 *
 * <p>Handed to the model in place of the full XML during a repair turn. A plan runs to thousands
 * of lines but its behaviour is captured by a few dozen names, so summarizing rather than pasting
 * cuts the healing prompt by an order of magnitude and keeps the model focused on the wiring
 * rather than the markup.
 *
 * @param samplerNames        every sampler in execution order
 * @param definedVariables    variables supplied by CSV feeds, extractors or user-defined variables
 * @param referencedVariables variables the plan reads via {@code ${...}}
 */
public record JmxStructure(
        List<String> samplerNames,
        List<String> definedVariables,
        List<String> referencedVariables) {

    public JmxStructure {
        samplerNames = List.copyOf(samplerNames);
        definedVariables = List.copyOf(definedVariables);
        referencedVariables = List.copyOf(referencedVariables);
    }

    /**
     * @return variables the plan reads but nothing defines — almost always a missed correlation,
     * and the single most common reason a generated plan returns 401
     */
    public List<String> unresolvedVariables() {
        return referencedVariables.stream()
                .filter(variable -> !definedVariables.contains(variable))
                .toList();
    }

    /** @return a compact briefing line for the model's repair turn. */
    public String describe() {
        return """
                Samplers: %s
                Defined variables: %s
                Referenced variables: %s
                Unresolved references: %s""".formatted(
                samplerNames, definedVariables, referencedVariables, unresolvedVariables());
    }
}
