package com.ai.jmeter.agent.domain.redaction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Covers the compliance vocabulary that records what never left the host. */
class RedactionDomainTest {

    private static RedactedSecret credential() {
        return new RedactedSecret(SecretCategory.CREDENTIAL,
                "${__P(agent.secret.credential.1)}", "eyJ.live.token");
    }

    private static RedactedSecret email() {
        return new RedactedSecret(SecretCategory.EMAIL,
                "${__P(agent.secret.email.1)}", "alice@corp.test");
    }

    @Nested
    @DisplayName("SecretCategory")
    class Categories {

        @ParameterizedTest
        @EnumSource(SecretCategory.class)
        @DisplayName("every category names its placeholder prefix")
        void namesPlaceholderPrefix(SecretCategory category) {
            assertThat(category.placeholderPrefix()).isNotBlank();
            assertThat(SecretCategory.valueOf(category.name())).isSameAs(category);
        }

        @Test
        @DisplayName("email is shape-preservable, credentials are not")
        void strictPolicyClassification() {
            assertThat(SecretCategory.CREDENTIAL.blocksStrictCompliance()).isTrue();
            assertThat(SecretCategory.PAYMENT_CARD.blocksStrictCompliance()).isTrue();
            assertThat(SecretCategory.EMAIL.blocksStrictCompliance()).isFalse();
        }
    }

    @Nested
    @DisplayName("RedactedSecret")
    class Secret {

        @Test
        @DisplayName("derives the property name an operator sets")
        void derivesPropertyName() {
            assertThat(credential().propertyName()).isEqualTo("agent.secret.credential.1");
        }

        @Test
        @DisplayName("never prints the secret, even through a log statement")
        void neverPrintsTheValue() {
            // These objects travel through logging and exception paths; a record's generated
            // toString would put a live credential straight into a log file.
            assertThat(credential().toString())
                    .doesNotContain("eyJ.live.token")
                    .contains("<redacted>")
                    .contains("CREDENTIAL");
        }

        @Test
        @DisplayName("rejects incomplete construction")
        void rejectsNulls() {
            assertThatNullPointerException().isThrownBy(
                    () -> new RedactedSecret(null, "p", "v")).withMessageContaining("category");
            assertThatNullPointerException().isThrownBy(
                    () -> new RedactedSecret(SecretCategory.EMAIL, null, "v"))
                    .withMessageContaining("placeholder");
            assertThatNullPointerException().isThrownBy(
                    () -> new RedactedSecret(SecretCategory.EMAIL, "p", null))
                    .withMessageContaining("secretValue");
        }
    }

    @Nested
    @DisplayName("RedactionResult")
    class Result {

        @Test
        @DisplayName("a clean capture reports nothing removed")
        void cleanCapture() {
            RedactionResult result = RedactionResult.clean("[]");

            assertThat(result.foundSecrets()).isFalse();
            assertThat(result.secrets()).isEmpty();
            assertThat(result.countsByCategory()).isEmpty();
            assertThat(result.strictPolicyViolations()).isEmpty();
            assertThat(result.propertyBindings()).isEmpty();
            assertThat(result.placeholders()).isEmpty();
            assertThat(result.redactedPayload()).isEqualTo("[]");
        }

        @Test
        @DisplayName("counts what was removed by category")
        void countsByCategory() {
            RedactionResult result =
                    new RedactionResult("safe", List.of(credential(), email(), email()));

            assertThat(result.countsByCategory())
                    .containsEntry(SecretCategory.CREDENTIAL, 1L)
                    .containsEntry(SecretCategory.EMAIL, 2L);
        }

        @Test
        @DisplayName("names only the categories a strict policy refuses")
        void strictPolicyViolations() {
            RedactionResult result = new RedactionResult("safe", List.of(credential(), email()));

            assertThat(result.strictPolicyViolations())
                    .containsExactly(SecretCategory.CREDENTIAL);
        }

        @Test
        @DisplayName("exposes the bindings JMeter needs at run time")
        void propertyBindings() {
            RedactionResult result = new RedactionResult("safe", List.of(credential(), email()));

            assertThat(result.propertyBindings())
                    .containsEntry("agent.secret.credential.1", "eyJ.live.token")
                    .containsEntry("agent.secret.email.1", "alice@corp.test");
        }

        @Test
        @DisplayName("collapses a duplicated placeholder onto one binding")
        void duplicatePlaceholdersCollapse() {
            RedactionResult result = new RedactionResult("safe", List.of(email(), email()));

            assertThat(result.propertyBindings()).hasSize(1);
            assertThat(result.placeholders()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("CompliancePolicyViolationException")
    class Violation {

        @Test
        @DisplayName("names what it refused and how to proceed")
        void explainsTheRefusal() {
            CompliancePolicyViolationException exception =
                    new CompliancePolicyViolationException(List.of(SecretCategory.PAYMENT_CARD));

            assertThat(exception.violations()).containsExactly(SecretCategory.PAYMENT_CARD);
            assertThat(exception.getMessage())
                    .contains("PAYMENT_CARD")
                    .contains("agent.compliance.strict-mode");
        }
    }
}
