package com.ai.jmeter.agent.adapter.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ContentDigest")
class ContentDigestTest {

    @Test
    @DisplayName("names the algorithm alongside the digest, so it stays verifiable later")
    void prefixesTheAlgorithm() {
        // A bare hex string in an audit record is unverifiable the day the algorithm changes and
        // nobody recorded which one produced it.
        assertThat(new ContentDigest().of("<plan/>"))
                .startsWith("sha256:")
                .hasSize("sha256:".length() + 64);
    }

    @Test
    @DisplayName("gives the same digest for the same content and a different one otherwise")
    void isDeterministic() {
        ContentDigest digest = new ContentDigest();

        assertThat(digest.of("<plan/>")).isEqualTo(digest.of("<plan/>"));
        assertThat(digest.of("<plan/>")).isNotEqualTo(digest.of("<plan></plan>"));
    }

    @Test
    @DisplayName("digests empty content rather than refusing it")
    void digestsEmptyContent() {
        assertThat(new ContentDigest().of("")).startsWith("sha256:");
    }

    @Test
    @DisplayName("says plainly when the JVM cannot provide the algorithm")
    void reportsAnUnavailableAlgorithm() {
        ContentDigest brittle = new ContentDigest("SHA-999");

        assertThatThrownBy(() -> brittle.of("<plan/>"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot provide SHA-999");
    }
}
