package com.ai.jmeter.agent.domain.redaction;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A capture with every detected secret substituted, plus the mapping needed to bind real values
 * back at execution time.
 *
 * @param redactedPayload the text that is safe to send to a model
 * @param secrets         what was removed, in first-seen order
 */
public record RedactionResult(String redactedPayload, List<RedactedSecret> secrets) {

    public RedactionResult {
        secrets = List.copyOf(secrets);
    }

    /** A capture that contained nothing sensitive. */
    public static RedactionResult clean(String payload) {
        return new RedactionResult(payload, List.of());
    }

    public boolean foundSecrets() {
        return !secrets.isEmpty();
    }

    /**
     * @return how many secrets of each category were removed — safe to log, unlike the secrets
     * themselves, and the basis of the compliance record for the run
     */
    public Map<SecretCategory, Long> countsByCategory() {
        return secrets.stream().collect(Collectors.groupingBy(
                RedactedSecret::category, Collectors.counting()));
    }

    /**
     * @return categories present that the strictest compliance policy refuses to let leave the
     * process, even substituted
     */
    public List<SecretCategory> strictPolicyViolations() {
        return secrets.stream()
                .map(RedactedSecret::category)
                .filter(SecretCategory::blocksStrictCompliance)
                .distinct()
                .sorted()
                .toList();
    }

    /** @return placeholder-to-value bindings an execution adapter supplies to JMeter. */
    public Map<String, String> propertyBindings() {
        return secrets.stream().collect(Collectors.toMap(
                RedactedSecret::propertyName,
                RedactedSecret::secretValue,
                (first, duplicate) -> first,
                LinkedHashMap::new));
    }

    /** @return the distinct placeholders substituted, for inclusion in the model's brief. */
    public List<String> placeholders() {
        return secrets.stream().map(RedactedSecret::placeholder).distinct().toList();
    }
}
