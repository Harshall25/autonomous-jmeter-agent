package com.ai.jmeter.agent.domain.eval;

import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.jmx.JmxStructure;
import java.util.List;
import java.util.Locale;

/**
 * An assertion about the plan the agent should have produced for a given capture.
 *
 * <p>Structural rather than textual on purpose. Asserting on the XML would fail every time the
 * model reordered an attribute, so the suite would be rewritten until it asserted nothing; these
 * assert on what the plan <em>does</em>, which is the thing that must not regress when a prompt
 * is edited or a model is upgraded.
 */
public sealed interface StructuralExpectation {

    /**
     * @param plan      the generated artifacts
     * @param structure what the plan resolves to once parsed
     * @return whether the expectation holds
     */
    boolean isSatisfiedBy(JmeterGenerationResult plan, JmxStructure structure);

    /** @return the expectation in the words a failure report should use. */
    String describe();

    /** The plan must exercise an endpoint whose sampler name contains this fragment. */
    record ExercisesSampler(String nameFragment) implements StructuralExpectation {

        @Override
        public boolean isSatisfiedBy(JmeterGenerationResult plan, JmxStructure structure) {
            return structure.samplerNames().stream().anyMatch(name -> containsIgnoringCase(
                    name, nameFragment));
        }

        @Override
        public String describe() {
            return "exercises a sampler named like '%s'".formatted(nameFragment);
        }
    }

    /**
     * The plan must define this variable — the assertion that catches a missed correlation, which
     * is the single most common way a generated plan is wrong in a way that still runs.
     */
    record CorrelatesVariable(String variableName) implements StructuralExpectation {

        @Override
        public boolean isSatisfiedBy(JmeterGenerationResult plan, JmxStructure structure) {
            return structure.definedVariables().stream()
                    .anyMatch(defined -> defined.equalsIgnoreCase(variableName));
        }

        @Override
        public String describe() {
            return "correlates the variable '%s'".formatted(variableName);
        }
    }

    /** The plan must parameterize from a CSV column of this name. */
    record ParameterizesColumn(String columnName) implements StructuralExpectation {

        @Override
        public boolean isSatisfiedBy(JmeterGenerationResult plan, JmxStructure structure) {
            String header = plan.csvTemplateContent().lines().findFirst().orElse("");
            return List.of(header.split(",")).stream()
                    .anyMatch(column -> column.strip().equalsIgnoreCase(columnName));
        }

        @Override
        public String describe() {
            return "parameterizes from the CSV column '%s'".formatted(columnName);
        }
    }

    /** The plan must cover at least this many endpoints. */
    record CoversAtLeast(int samplers) implements StructuralExpectation {

        @Override
        public boolean isSatisfiedBy(JmeterGenerationResult plan, JmxStructure structure) {
            return structure.samplerNames().size() >= samplers;
        }

        @Override
        public String describe() {
            return "covers at least %d sampler(s)".formatted(samplers);
        }
    }

    /**
     * Every variable the plan reads must be defined somewhere in it.
     *
     * <p>The assertion a plan can fail while looking perfectly reasonable: an unresolved
     * {@code ${token}} is sent literally, and the run comes back 401 with nothing in the XML to
     * suggest why.
     */
    record ResolvesEveryVariable() implements StructuralExpectation {

        @Override
        public boolean isSatisfiedBy(JmeterGenerationResult plan, JmxStructure structure) {
            return structure.unresolvedVariables().isEmpty();
        }

        @Override
        public String describe() {
            return "leaves no variable reference unresolved";
        }
    }

    private static boolean containsIgnoringCase(String haystack, String needle) {
        return haystack.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
