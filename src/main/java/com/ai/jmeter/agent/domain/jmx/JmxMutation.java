package com.ai.jmeter.agent.domain.jmx;

import java.util.List;

/**
 * A single structural edit to a JMeter test plan.
 *
 * <p>This type is the pivot of the agent's repair strategy. Asking a model to re-emit an entire
 * plan on every healing turn is slow, expensive, and unsafe: the model rewrites thousands of lines
 * to change one extractor, and silently drops elements it was never asked to touch. Asking it for
 * a list of mutations instead makes each repair small, reviewable as a diff, and incapable of
 * producing malformed XML — the plan is edited by a parser, not concatenated by a language model.
 *
 * <p>A sealed hierarchy of pure records, so the applying adapter must handle every case and adding
 * a mutation kind is a compile error until it is implemented.
 */
public sealed interface JmxMutation
        permits JmxMutation.AddJsonPathExtractor,
                JmxMutation.AddRegexExtractor,
                JmxMutation.SetHeader,
                JmxMutation.AddCsvDataSet,
                JmxMutation.ConfigureThreadGroup,
                JmxMutation.ReplaceLiteralWithVariable,
                JmxMutation.RemoveElement {

    /** @return a one-line, human-readable rendering for the run's audit trail. */
    String describe();

    /**
     * Captures a value from a JSON response body into a variable.
     *
     * @param samplerName  the sampler whose response is mined, by {@code testname}
     * @param variableName the JMeter variable to define
     * @param jsonPath     the JSONPath expression, for example {@code $.access_token}
     * @param defaultValue what the variable holds when the path does not match
     */
    record AddJsonPathExtractor(
            String samplerName, String variableName, String jsonPath, String defaultValue)
            implements JmxMutation {

        @Override
        public String describe() {
            return "extract ${%s} from %s via JSONPath %s".formatted(
                    variableName, samplerName, jsonPath);
        }
    }

    /**
     * Captures a value by regular expression, for responses that are not JSON — including headers,
     * which is where {@code Set-Cookie} and {@code Location} correlation live.
     *
     * @param useHeaders whether to match against response headers rather than the body
     */
    record AddRegexExtractor(
            String samplerName,
            String variableName,
            String regex,
            String template,
            String defaultValue,
            boolean useHeaders) implements JmxMutation {

        @Override
        public String describe() {
            return "extract ${%s} from %s via regex %s (%s)".formatted(
                    variableName, samplerName, regex, useHeaders ? "headers" : "body");
        }
    }

    /**
     * Sets a request header, creating the Header Manager if the sampler has none.
     *
     * @param samplerName the sampler to scope the header to; blank applies it plan-wide
     */
    record SetHeader(String samplerName, String headerName, String headerValue)
            implements JmxMutation {

        /** @return {@code true} when this header applies to every sampler in the plan. */
        public boolean isPlanWide() {
            return samplerName == null || samplerName.isBlank();
        }

        @Override
        public String describe() {
            return "set header %s on %s".formatted(
                    headerName, isPlanWide() ? "the whole plan" : samplerName);
        }
    }

    /** Declares the CSV feed backing the plan's parameterized values. */
    record AddCsvDataSet(String filename, List<String> variableNames) implements JmxMutation {

        public AddCsvDataSet {
            variableNames = List.copyOf(variableNames);
        }

        @Override
        public String describe() {
            return "bind CSV %s supplying %s".formatted(filename, variableNames);
        }
    }

    /**
     * Sets the workload shape. Separated from generation because the same plan is run as a
     * one-thread correctness check first and only then scaled to a realistic load.
     */
    record ConfigureThreadGroup(int threads, int rampUpSeconds, int loops)
            implements JmxMutation {

        @Override
        public String describe() {
            return "set workload to %d thread(s), %ds ramp-up, %d loop(s)".formatted(
                    threads, rampUpSeconds, loops);
        }
    }

    /**
     * Swaps a hardcoded literal for a variable reference everywhere it appears.
     *
     * <p>This is the parameterization primitive: the model identifies that {@code alice@corp.test}
     * is test data rather than structure, and this turns every occurrence into {@code ${username}}
     * without the model having to reproduce the surrounding XML correctly.
     */
    record ReplaceLiteralWithVariable(String literal, String variableName)
            implements JmxMutation {

        @Override
        public String describe() {
            return "parameterize literal into ${%s}".formatted(variableName);
        }
    }

    /** Deletes an element by {@code testname}, used to drop samplers that cannot be made to pass. */
    record RemoveElement(String testName) implements JmxMutation {

        @Override
        public String describe() {
            return "remove element " + testName;
        }
    }
}
