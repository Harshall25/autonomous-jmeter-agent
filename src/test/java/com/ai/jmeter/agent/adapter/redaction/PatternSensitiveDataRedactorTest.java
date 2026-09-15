package com.ai.jmeter.agent.adapter.redaction;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.jmeter.agent.domain.redaction.RedactedSecret;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import com.ai.jmeter.agent.domain.redaction.SecretCategory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("PatternSensitiveDataRedactor")
class PatternSensitiveDataRedactorTest {

    private final PatternSensitiveDataRedactor redactor = new PatternSensitiveDataRedactor();

    @Nested
    @DisplayName("credentials")
    class Credentials {

        @Test
        @DisplayName("strips a bearer token but leaves the header structure visible")
        void redactsBearerToken() {
            RedactionResult result = redactor.redact(
                    "{\"Authorization\":\"Bearer abc123def456ghi789\"}");

            assertThat(result.redactedPayload())
                    .doesNotContain("abc123def456ghi789")
                    .as("the model must still see that an Authorization header exists")
                    .contains("Authorization")
                    .contains("Bearer ")
                    .contains("${__P(agent.secret.credential.1)}");
            assertThat(result.secrets())
                    .extracting(RedactedSecret::category)
                    .containsExactly(SecretCategory.CREDENTIAL);
        }

        @Test
        @DisplayName("strips basic auth credentials")
        void redactsBasicAuth() {
            RedactionResult result = redactor.redact("Authorization: Basic YWxpY2U6czNjcmV0AA==");

            assertThat(result.redactedPayload()).doesNotContain("YWxpY2U6czNjcmV0");
            assertThat(result.foundSecrets()).isTrue();
        }

        @Test
        @DisplayName("strips a bare JWT that carries no Bearer prefix")
        void redactsBareJwt() {
            RedactionResult result = redactor.redact(
                    "{\"id_token\":\"eyJhbGciOiJIUzI1NiJ9.cGF5bG9hZA.c2lnbmF0dXJl\"}");

            assertThat(result.redactedPayload()).doesNotContain("eyJhbGciOiJIUzI1NiJ9");
            assertThat(result.secrets()).hasSize(1);
        }

        @Test
        @DisplayName("strips a PEM private key whole, across its line breaks")
        void redactsPrivateKey() {
            RedactionResult result = redactor.redact("""
                    -----BEGIN RSA PRIVATE KEY-----
                    MIIEowIBAAKCAQEAx4fm7dngEmOULNmAs1IGZ9Apfzh
                    -----END RSA PRIVATE KEY-----""");

            assertThat(result.redactedPayload()).doesNotContain("MIIEowIBAAKCAQEA");
            assertThat(result.secrets())
                    .extracting(RedactedSecret::category)
                    .containsExactly(SecretCategory.PRIVATE_KEY);
        }
    }

    @Nested
    @DisplayName("session and key material")
    class SessionMaterial {

        @Test
        @DisplayName("strips a session cookie value")
        void redactsSessionCookie() {
            RedactionResult result = redactor.redact("Cookie: JSESSIONID=A1B2C3D4E5F6G7H8; theme=dark");

            assertThat(result.redactedPayload())
                    .doesNotContain("A1B2C3D4E5F6G7H8")
                    .as("non-sensitive cookies stay, so the request still looks realistic")
                    .contains("theme=dark");
            assertThat(result.secrets())
                    .extracting(RedactedSecret::category)
                    .containsExactly(SecretCategory.SESSION);
        }

        @Test
        @DisplayName("strips an API key given as a header or a query parameter")
        void redactsApiKey() {
            RedactionResult result = redactor.redact("{\"api_key\": \"sk-live-9f8e7d6c5b4a\"}");

            assertThat(result.redactedPayload()).doesNotContain("sk-live-9f8e7d6c5b4a");
            assertThat(result.secrets())
                    .extracting(RedactedSecret::category)
                    .containsExactly(SecretCategory.API_KEY);
        }

        @Test
        @DisplayName("strips a password out of a form body")
        void redactsPassword() {
            RedactionResult result = redactor.redact("username=alice&password=hunter2xyz");

            assertThat(result.redactedPayload())
                    .doesNotContain("hunter2xyz")
                    .contains("username=alice");
        }
    }

    @Nested
    @DisplayName("personal data")
    class PersonalData {

        @Test
        @DisplayName("strips a payment card number")
        void redactsPaymentCard() {
            RedactionResult result = redactor.redact("{\"pan\":\"4111 1111 1111 1111\"}");

            assertThat(result.redactedPayload()).doesNotContain("4111");
            assertThat(result.secrets())
                    .extracting(RedactedSecret::category)
                    .containsExactly(SecretCategory.PAYMENT_CARD);
        }

        @Test
        @DisplayName("leaves long numbers that are not cards alone")
        void keepsNonCardNumbers() {
            // Without the Luhn check this would redact most identifiers in a capture and
            // destroy the traffic shape the model needs to reason about.
            RedactionResult result = redactor.redact("{\"orderId\":\"1234567890123\"}");

            assertThat(result.redactedPayload()).contains("1234567890123");
            assertThat(result.foundSecrets()).isFalse();
        }

        @Test
        @DisplayName("leaves a digit run that is too short to be a card")
        void ignoresShortDigitRuns() {
            assertThat(redactor.redact("{\"qty\":\"42\"}").foundSecrets()).isFalse();
        }

        @Test
        @DisplayName("strips a national identifier")
        void redactsNationalId() {
            RedactionResult result = redactor.redact("{\"ssn\":\"123-45-6789\"}");

            assertThat(result.redactedPayload()).doesNotContain("123-45-6789");
            assertThat(result.secrets())
                    .extracting(RedactedSecret::category)
                    .containsExactly(SecretCategory.NATIONAL_ID);
        }

        @Test
        @DisplayName("strips an email address")
        void redactsEmail() {
            RedactionResult result = redactor.redact("{\"email\":\"alice@corp.test\"}");

            assertThat(result.redactedPayload()).doesNotContain("alice@corp.test");
            assertThat(result.secrets())
                    .extracting(RedactedSecret::category)
                    .containsExactly(SecretCategory.EMAIL);
        }
    }

    @Nested
    @DisplayName("substitution behaviour")
    class Substitution {

        @Test
        @DisplayName("maps a repeated value onto one placeholder so it stays correlatable")
        void collapsesRepeatedValues() {
            // A session cookie repeated across requests must remain visibly the same value, or
            // the model will not recognize it as something to correlate.
            RedactionResult result = redactor.redact(
                    "req1 JSESSIONID=SAMESESSION123 ... req2 JSESSIONID=SAMESESSION123");

            assertThat(result.secrets()).hasSize(1);
            assertThat(result.placeholders()).hasSize(1);
            assertThat(result.redactedPayload().split("agent\\.secret\\.session\\.1", -1))
                    .hasSize(3);
        }

        @Test
        @DisplayName("numbers distinct values of the same category separately")
        void numbersDistinctValues() {
            RedactionResult result = redactor.redact(
                    "a@corp.test and b@corp.test");

            assertThat(result.placeholders()).containsExactly(
                    "${__P(agent.secret.email.1)}", "${__P(agent.secret.email.2)}");
        }

        @Test
        @DisplayName("leaves a clean capture byte-for-byte identical")
        void cleanPayloadIsUntouched() {
            String payload = "[{\"method\":\"GET\",\"url\":\"https://api.shop.test/v1/orders\"}]";

            RedactionResult result = redactor.redact(payload);

            assertThat(result.redactedPayload()).isEqualTo(payload);
            assertThat(result.foundSecrets()).isFalse();
            assertThat(result.secrets()).isEmpty();
        }

        @Test
        @DisplayName("exposes the bindings needed to restore real values at run time")
        void exposesPropertyBindings() {
            RedactionResult result = redactor.redact("Authorization: Bearer abc123def456ghi789");

            assertThat(result.propertyBindings())
                    .containsEntry("agent.secret.credential.1", "abc123def456ghi789");
        }

        @Test
        @DisplayName("counts what it removed without revealing any of it")
        void reportsCountsByCategory() {
            RedactionResult result = redactor.redact(
                    "Bearer abc123def456ghi789 for alice@corp.test and bob@corp.test");

            assertThat(result.countsByCategory())
                    .containsEntry(SecretCategory.CREDENTIAL, 1L)
                    .containsEntry(SecretCategory.EMAIL, 2L);
        }
    }
}
