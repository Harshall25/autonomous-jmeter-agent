package com.ai.jmeter.agent.adapter.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.governance.RunManifest;
import com.ai.jmeter.agent.domain.governance.TenantId;
import com.ai.jmeter.agent.port.ProvenanceException;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("Manifest signing")
class ManifestSigningTest {

    private static final String KEY = "a-thirty-two-character-test-key!!";
    private static final String OTHER_KEY = "a-different-thirty-two-char-key!!";

    private static RunManifest manifest() {
        return RunManifest.unsigned(
                "run-1", new TenantId("acme"), "dana", "prompts@v3",
                Map.of("GENERATION", "claude-3-5-sonnet"),
                Map.of("auto_test.jmx", "sha256:abc"),
                Instant.parse("2025-01-02T03:04:05Z"));
    }

    @Nested
    @DisplayName("with a key configured")
    class WithAKey {

        private final HmacManifestSigner signer = new HmacManifestSigner(KEY);

        @Test
        @DisplayName("verifies a manifest that has not been touched")
        void verifiesAnUntouchedManifest() {
            String payload = manifest().canonicalPayload();

            assertThat(signer.verify(payload, signer.sign(payload))).isTrue();
        }

        @Test
        @DisplayName("rejects a manifest edited after it was signed")
        void rejectsAnEditedManifest() {
            // The whole point: an audit trail anyone with write access can rewrite is not one.
            RunManifest original = manifest();
            String signature = signer.sign(original.canonicalPayload());

            RunManifest tampered = original.approvedBy("an-approver-who-never-approved");

            assertThat(signer.verify(tampered.canonicalPayload(), signature)).isFalse();
        }

        @Test
        @DisplayName("rejects a signature produced with a different key")
        void rejectsAnotherKeysSignature() {
            String payload = manifest().canonicalPayload();
            String forged = new HmacManifestSigner(OTHER_KEY).sign(payload);

            assertThat(signer.verify(payload, forged)).isFalse();
        }

        @Test
        @DisplayName("rejects a missing signature rather than treating it as a match")
        void rejectsAnAbsentSignature() {
            String payload = manifest().canonicalPayload();

            assertThat(signer.verify(payload, null)).isFalse();
            assertThat(signer.verify(payload, "   ")).isFalse();
        }

        @Test
        @DisplayName("produces the same signature for the same manifest every time")
        void isDeterministic() {
            String payload = manifest().canonicalPayload();

            assertThat(signer.sign(payload)).isEqualTo(signer.sign(payload));
        }

        @Test
        @DisplayName("produces a hex signature of the length SHA-256 implies")
        void producesAFullLengthDigest() {
            assertThat(signer.sign("anything"))
                    .hasSize(64)
                    .matches("[0-9a-f]+");
        }
    }

    @Nested
    @DisplayName("without a usable key")
    class WithoutAKey {

        @Test
        @DisplayName("refuses a key short enough to have been pasted from an example")
        void refusesAWeakKey() {
            // Provenance signed with a guessable key is indistinguishable from unprotected, and
            // reads in an audit exactly like the real thing.
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HmacManifestSigner("hunter2"))
                    .withMessageContaining("at least 32 characters");
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> new HmacManifestSigner(null))
                    .withMessageContaining("at least 32 characters");
        }

        @Test
        @DisplayName("refuses to sign rather than returning something that proves nothing")
        void refusesToSign() {
            UnconfiguredManifestSigner signer = new UnconfiguredManifestSigner();

            assertThatThrownBy(() -> signer.sign("payload"))
                    .isInstanceOf(ProvenanceException.class)
                    .hasMessageContaining("agent.tenancy.signing-key");
        }

        @Test
        @DisplayName("reports a JVM that cannot provide the algorithm rather than failing opaquely")
        void reportsAnUnavailableAlgorithm() {
            HmacManifestSigner brittle = new HmacManifestSigner(KEY, "HmacNoSuchThing");

            assertThatThrownBy(() -> brittle.sign("payload"))
                    .isInstanceOf(ProvenanceException.class)
                    .hasMessageContaining("Unable to sign the run manifest");
        }

        @Test
        @DisplayName("reports nothing as verified, because nothing can be")
        void verifiesNothing() {
            assertThat(new UnconfiguredManifestSigner().verify("payload", "deadbeef")).isFalse();
        }
    }
}
