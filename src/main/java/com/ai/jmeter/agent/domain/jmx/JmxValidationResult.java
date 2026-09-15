package com.ai.jmeter.agent.domain.jmx;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The verdict of validating a plan before it is executed.
 *
 * <p>Errors mean JMeter would reject or no-op the plan; warnings mean it would run but probably
 * not measure what was intended — an unresolved variable reference being the canonical case.
 *
 * @param errors   defects that make execution pointless
 * @param warnings defects that make the results untrustworthy
 */
public record JmxValidationResult(List<String> errors, List<String> warnings) {

    public JmxValidationResult {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public static JmxValidationResult valid() {
        return new JmxValidationResult(List.of(), List.of());
    }

    public static JmxValidationResult error(String message) {
        return new JmxValidationResult(List.of(message), List.of());
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    /**
     * @return a digest suitable for feeding straight back to the model, phrased as findings rather
     * than as an execution report
     */
    public String describe() {
        return Stream.concat(
                        errors.stream().map(error -> "ERROR: " + error),
                        warnings.stream().map(warning -> "WARNING: " + warning))
                .collect(Collectors.joining("\n"));
    }
}
