package com.ai.jmeter.agent.adapter.redaction;

import com.ai.jmeter.agent.domain.redaction.RedactedSecret;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import com.ai.jmeter.agent.domain.redaction.SecretCategory;
import com.ai.jmeter.agent.port.SensitiveDataRedactorPort;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: detects secrets by structural pattern and replaces them with JMeter property
 * references.
 *
 * <p>Substitution is deliberately not deletion. Replacing a bearer token with
 * {@code ${__P(agent.secret.credential.1)}} keeps the shape of the request intact, so the model
 * still sees that an {@code Authorization} header exists and still writes the correlation logic
 * for it — while the actual credential stays on the host and is supplied as a JMeter property at
 * run time.
 *
 * <p>Identical values collapse onto one placeholder. That matters for correctness as much as for
 * tidiness: a session cookie repeated across eight requests must remain visibly <em>the same</em>
 * value, or the model will not recognize it as something to correlate.
 */
public final class PatternSensitiveDataRedactor implements SensitiveDataRedactorPort {

    private static final Logger log = LoggerFactory.getLogger(PatternSensitiveDataRedactor.class);

    /**
     * Ordered detectors. Order matters: the most specific structural patterns run first so that a
     * JWT inside an {@code Authorization} header is claimed as a credential rather than being
     * partially matched by a looser rule later.
     */
    private static final List<SecretDetector> DETECTORS = List.of(
            new SecretDetector(SecretCategory.PRIVATE_KEY, Pattern.compile(
                    "-----BEGIN[A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END[A-Z ]*PRIVATE KEY-----")),
            new SecretDetector(SecretCategory.CREDENTIAL, Pattern.compile(
                    "(?i)(?<=bearer )[A-Za-z0-9._~+/=-]{12,}")),
            new SecretDetector(SecretCategory.CREDENTIAL, Pattern.compile(
                    "(?i)(?<=basic )[A-Za-z0-9+/=]{12,}")),
            // A JWT anywhere, even unprefixed: three base64url segments separated by dots.
            new SecretDetector(SecretCategory.CREDENTIAL, Pattern.compile(
                    "eyJ[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}\\.[A-Za-z0-9_-]{4,}")),
            new SecretDetector(SecretCategory.SESSION, Pattern.compile(
                    "(?i)(?<=\\b(?:jsessionid|phpsessid|sessionid|sid|csrf_token|xsrf-token)=)"
                            + "[A-Za-z0-9._-]{8,}")),
            new SecretDetector(SecretCategory.API_KEY, Pattern.compile(
                    "(?i)(?<=\\b(?:api[_-]?key|apikey|access[_-]?token|client[_-]?secret|"
                            + "password|passwd|pwd)[\"']?\\s*[=:]\\s*[\"']?)[^\\s\"',&}]{6,}")),
            new SecretDetector(SecretCategory.PAYMENT_CARD, Pattern.compile(
                    "\\b(?:\\d[ -]*?){13,19}\\b"), PatternSensitiveDataRedactor::passesLuhn),
            new SecretDetector(SecretCategory.NATIONAL_ID, Pattern.compile(
                    "\\b\\d{3}-\\d{2}-\\d{4}\\b")),
            new SecretDetector(SecretCategory.EMAIL, Pattern.compile(
                    "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")));

    private static final String PROPERTY_TEMPLATE = "${__P(agent.secret.%s.%d)}";

    @Override
    public RedactionResult redact(String payload) {
        Map<String, RedactedSecret> byValue = new LinkedHashMap<>();
        Map<SecretCategory, Integer> sequenceByCategory = new LinkedHashMap<>();
        String working = payload;

        for (SecretDetector detector : DETECTORS) {
            working = detector.replaceIn(working, byValue, sequenceByCategory);
        }

        if (byValue.isEmpty()) {
            return RedactionResult.clean(payload);
        }

        List<RedactedSecret> secrets = List.copyOf(byValue.values());
        RedactionResult result = new RedactionResult(working, secrets);
        // Counts only — logging the values would defeat the entire purpose of this class.
        log.info("Redacted {} secret(s) before model egress: {}",
                secrets.size(), result.countsByCategory());
        return result;
    }

    /**
     * Rejects the many 13-to-19 digit runs that are not payment cards — timestamps, order numbers,
     * durations. Without the checksum this detector would redact most numeric identifiers in a
     * capture and destroy the traffic shape the model needs.
     */
    private static boolean passesLuhn(String candidate) {
        // Length is already guaranteed by the pattern's {13,19} quantifier, which counts digits
        // rather than characters, so only the checksum is left to decide.
        String digits = candidate.replaceAll("[ -]", "");
        int sum = 0;
        boolean doubling = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (doubling) {
                digit *= 2;
                if (digit > 9) {
                    digit -= 9;
                }
            }
            sum += digit;
            doubling = !doubling;
        }
        return sum % 10 == 0;
    }

    /** One pattern plus an optional semantic check that filters structural false positives. */
    private record SecretDetector(
            SecretCategory category,
            Pattern pattern,
            java.util.function.Predicate<String> accepts) {

        SecretDetector(SecretCategory category, Pattern pattern) {
            this(category, pattern, candidate -> true);
        }

        String replaceIn(
                String payload,
                Map<String, RedactedSecret> byValue,
                Map<SecretCategory, Integer> sequenceByCategory) {

            Matcher matcher = pattern.matcher(payload);
            StringBuilder rewritten = new StringBuilder();
            while (matcher.find()) {
                String found = matcher.group();
                if (!accepts.test(found)) {
                    matcher.appendReplacement(rewritten, Matcher.quoteReplacement(found));
                    continue;
                }
                RedactedSecret secret = byValue.computeIfAbsent(found, value -> {
                    int sequence = sequenceByCategory.merge(category, 1, Integer::sum);
                    return new RedactedSecret(
                            category,
                            PROPERTY_TEMPLATE.formatted(category.placeholderPrefix(), sequence),
                            value);
                });
                matcher.appendReplacement(
                        rewritten, Matcher.quoteReplacement(secret.placeholder()));
            }
            matcher.appendTail(rewritten);
            return rewritten.toString();
        }
    }
}
