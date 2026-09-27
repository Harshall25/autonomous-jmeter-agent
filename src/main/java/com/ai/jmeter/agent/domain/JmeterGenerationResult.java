package com.ai.jmeter.agent.domain;

import java.util.List;
import java.util.Objects;

/**
 * The structured answer the agent demands from the LLM on every generation and every
 * self-healing turn.
 *
 * <p>Asking the model for a typed payload rather than a blob of XML is what makes the
 * agentic loop mechanical: the orchestrator can write the script and its test data to disk,
 * and can log <em>why</em> the model made the choices it did, without parsing prose.
 *
 * @param jmxXmlContent      the complete Apache JMeter test plan as {@code .jmx} XML
 * @param csvTemplateContent the CSV Data Set Config payload backing the parameterized values
 * @param identifiedVariables the variable names the model correlated or parameterized
 * @param executionRationale the model's explanation of the plan it produced
 */
public record JmeterGenerationResult(
        String jmxXmlContent,
        String csvTemplateContent,
        List<String> identifiedVariables,
        String executionRationale) {

    /**
     * Normalizes the payload so downstream code never has to null-check: the variable list
     * becomes an immutable copy, and the two optional text payloads default to empty.
     *
     * @throws IllegalArgumentException if the model returned no usable JMX content, which
     *                                  makes the whole result unusable
     */
    public JmeterGenerationResult {
        if (jmxXmlContent == null || jmxXmlContent.isBlank()) {
            throw new IllegalArgumentException("jmxXmlContent must not be blank");
        }
        csvTemplateContent = csvTemplateContent == null ? "" : csvTemplateContent;
        executionRationale = executionRationale == null ? "" : executionRationale;
        identifiedVariables = identifiedVariables == null
                ? List.of()
                : List.copyOf(identifiedVariables.stream().filter(Objects::nonNull).toList());
    }
}
