package com.ai.jmeter.agent.adapter.ai;

import com.ai.jmeter.agent.domain.jmx.JmxMutation;
import com.ai.jmeter.agent.domain.jmx.JmxRepairPlan;
import com.ai.jmeter.agent.port.JmeterAgentException;
import java.util.List;
import java.util.Locale;

/**
 * The wire shape of a repair proposal, sitting between the model and the domain.
 *
 * <p>{@link JmxMutation} is a sealed hierarchy, which is the right model for the domain but a poor
 * one for JSON Schema: a language model cannot reliably emit a discriminated union, and binding
 * one would require Jackson annotations that the architecture forbids in the domain. So the model
 * fills in one flat, permissive record per edit, and this class narrows it into the sealed type —
 * the translation an adapter exists to perform.
 *
 * @param diagnosis           what the model concluded was wrong
 * @param fullRewriteRequired set when the plan is judged beyond patching
 * @param mutations           the proposed edits
 */
public record RepairPlanResponse(
        String diagnosis, boolean fullRewriteRequired, List<MutationCommand> mutations) {

    /**
     * One proposed edit. Every field is optional because which ones matter depends on
     * {@code type}; the mapping below enforces the ones each kind actually requires.
     *
     * @param type one of {@code jsonPathExtractor}, {@code regexExtractor}, {@code setHeader},
     *             {@code csvDataSet}, {@code threadGroup}, {@code parameterize}, {@code remove}
     */
    public record MutationCommand(
            String type,
            String samplerName,
            String variableName,
            String jsonPath,
            String regex,
            String template,
            String defaultValue,
            Boolean useHeaders,
            String headerName,
            String headerValue,
            String filename,
            List<String> variableNames,
            Integer threads,
            Integer rampUpSeconds,
            Integer loops,
            String literal,
            String testName) {
    }

    /**
     * Narrows the model's reply into domain mutations.
     *
     * @return the typed repair plan
     * @throws JmeterAgentException if the model names an edit kind that does not exist, or omits a
     *                              field that kind requires
     */
    public JmxRepairPlan toDomain() {
        if (mutations == null || mutations.isEmpty()) {
            return JmxRepairPlan.rewrite(diagnosis);
        }
        return new JmxRepairPlan(
                mutations.stream().map(RepairPlanResponse::toMutation).toList(),
                diagnosis,
                fullRewriteRequired);
    }

    private static JmxMutation toMutation(MutationCommand command) {
        String type = required(command.type(), "type", "<unknown>").toLowerCase(Locale.ROOT);
        return switch (type) {
            case "jsonpathextractor" -> new JmxMutation.AddJsonPathExtractor(
                    required(command.samplerName(), "samplerName", type),
                    required(command.variableName(), "variableName", type),
                    required(command.jsonPath(), "jsonPath", type),
                    orDefault(command.defaultValue(), "NOT_FOUND"));
            case "regexextractor" -> new JmxMutation.AddRegexExtractor(
                    required(command.samplerName(), "samplerName", type),
                    required(command.variableName(), "variableName", type),
                    required(command.regex(), "regex", type),
                    orDefault(command.template(), "$1$"),
                    orDefault(command.defaultValue(), "NOT_FOUND"),
                    Boolean.TRUE.equals(command.useHeaders()));
            case "setheader" -> new JmxMutation.SetHeader(
                    orDefault(command.samplerName(), ""),
                    required(command.headerName(), "headerName", type),
                    orDefault(command.headerValue(), ""));
            case "csvdataset" -> new JmxMutation.AddCsvDataSet(
                    orDefault(command.filename(), "test_data.csv"),
                    command.variableNames() == null ? List.of() : command.variableNames());
            case "threadgroup" -> new JmxMutation.ConfigureThreadGroup(
                    orDefault(command.threads(), 1),
                    orDefault(command.rampUpSeconds(), 1),
                    orDefault(command.loops(), 1));
            case "parameterize" -> new JmxMutation.ReplaceLiteralWithVariable(
                    required(command.literal(), "literal", type),
                    required(command.variableName(), "variableName", type));
            case "remove" -> new JmxMutation.RemoveElement(
                    required(command.testName(), "testName", type));
            default -> throw new JmeterAgentException(
                    "Model proposed an unknown mutation type '%s'".formatted(command.type()));
        };
    }

    private static String required(String value, String field, String type) {
        if (value == null || value.isBlank()) {
            throw new JmeterAgentException(
                    "Mutation of type '%s' is missing required field '%s'".formatted(type, field));
        }
        return value;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int orDefault(Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
