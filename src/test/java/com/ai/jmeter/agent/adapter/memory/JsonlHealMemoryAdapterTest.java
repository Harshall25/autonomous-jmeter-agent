package com.ai.jmeter.agent.adapter.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.jmeter.agent.domain.memory.HealPrecedent;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("JsonlHealMemoryAdapter")
class JsonlHealMemoryAdapterTest {

    @TempDir
    Path tempDir;

    private Path store;
    private JsonlHealMemoryAdapter memory;

    @BeforeEach
    void setUp() {
        store = tempDir.resolve("memory/heal-memory.jsonl");
        memory = new JsonlHealMemoryAdapter(new ObjectMapper(), store);
    }

    private static HealPrecedent precedent(String signature) {
        return new HealPrecedent(
                signature,
                "Login response was never mined for the bearer token",
                List.of("extract ${auth_token} from login via JSONPath $.access_token"),
                true);
    }

    @Nested
    @DisplayName("recording")
    class Recording {

        @Test
        @DisplayName("creates the store on first write")
        void createsStore() {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));

            assertThat(store).exists();
        }

        @Test
        @DisplayName("appends rather than overwriting, so history accumulates")
        void appends() throws IOException {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));
            memory.remember(precedent("SAMPLE_FAILURE|403:orders"));

            assertThat(Files.readAllLines(store)).hasSize(2);
        }

        @Test
        @DisplayName("never fails a run when the store cannot be written")
        void writeFailureIsNonFatal() throws IOException {
            // Memory is an optimization. Losing a write must degrade the agent to a cold start,
            // not abort a run that is otherwise succeeding.
            Path blocked = tempDir.resolve("blocked");
            Files.writeString(blocked, "not a directory");
            JsonlHealMemoryAdapter brittle = new JsonlHealMemoryAdapter(
                    new ObjectMapper(), blocked.resolve("nested/heal-memory.jsonl"));

            brittle.remember(precedent("SAMPLE_FAILURE|401:login"));

            assertThat(brittle.recall("SAMPLE_FAILURE|401:login", 3)).isEmpty();
        }
    }

    @Nested
    @DisplayName("recall")
    class Recall {

        @Test
        @DisplayName("returns nothing before anything has been learned")
        void emptyStore() {
            assertThat(memory.recall("SAMPLE_FAILURE|401:login", 3)).isEmpty();
        }

        @Test
        @DisplayName("finds an exact repeat of a past failure")
        void findsExactMatch() {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));

            List<HealPrecedent> recalled = memory.recall("SAMPLE_FAILURE|401:login", 3);

            assertThat(recalled).hasSize(1);
            assertThat(recalled.get(0).diagnosis()).contains("bearer token");
            assertThat(recalled.get(0).worked()).isTrue();
        }

        @Test
        @DisplayName("finds a failure that resembles a past one without matching it exactly")
        void findsSimilarMatch() {
            // Failures are similar far more often than identical, so exact-match lookup would
            // almost never pay off.
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));

            assertThat(memory.recall("SAMPLE_FAILURE|401:login,401:profile", 3)).hasSize(1);
        }

        @Test
        @DisplayName("ignores a failure with nothing in common")
        void ignoresUnrelatedFailures() {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));

            assertThat(memory.recall("NO_SAMPLES", 3)).isEmpty();
        }

        @Test
        @DisplayName("ranks the closest match first")
        void ranksBySimilarity() {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));
            memory.remember(precedent("SAMPLE_FAILURE|401:login,403:orders,500:cart"));

            List<HealPrecedent> recalled = memory.recall("SAMPLE_FAILURE|401:login", 3);

            assertThat(recalled.get(0).signature()).isEqualTo("SAMPLE_FAILURE|401:login");
        }

        @Test
        @DisplayName("honours the requested limit")
        void honoursLimit() {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));
            memory.remember(precedent("SAMPLE_FAILURE|401:orders"));
            memory.remember(precedent("SAMPLE_FAILURE|401:profile"));

            assertThat(memory.recall("SAMPLE_FAILURE|401:login", 2)).hasSize(2);
        }

        @Test
        @DisplayName("recalls a repair that did not work, so it is not proposed again")
        void recallsFailedRepairs() {
            memory.remember(new HealPrecedent(
                    "SAMPLE_FAILURE|401:login", "guessed wrong", List.of("set header X"), false));

            List<HealPrecedent> recalled = memory.recall("SAMPLE_FAILURE|401:login", 3);

            assertThat(recalled.get(0).worked()).isFalse();
            assertThat(recalled.get(0).describe()).contains("did NOT fix it");
        }

        @Test
        @DisplayName("ignores blank lines in the store")
        void ignoresBlankLines() throws IOException {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));
            Files.writeString(store, "\n\n", java.nio.file.StandardOpenOption.APPEND);

            assertThat(memory.recall("SAMPLE_FAILURE|401:login", 3)).hasSize(1);
        }

        @Test
        @DisplayName("degrades to whatever parsed cleanly when the store is corrupt")
        void toleratesCorruptStore() throws IOException {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));
            Files.writeString(store, "{not json at all\n", java.nio.file.StandardOpenOption.APPEND);

            assertThat(memory.recall("SAMPLE_FAILURE|401:login", 3)).hasSize(1);
        }

        @Test
        @DisplayName("ignores a stored entry whose own signature is empty")
        void ignoresEmptyStoredSignature() {
            memory.remember(new HealPrecedent("", "d", List.of("edit"), true));

            assertThat(memory.recall("SAMPLE_FAILURE|401:login", 3)).isEmpty();
        }

        @Test
        @DisplayName("treats an empty signature as matching nothing")
        void emptySignatureMatchesNothing() {
            memory.remember(precedent("SAMPLE_FAILURE|401:login"));

            assertThat(memory.recall("", 3)).isEmpty();
        }
    }
}
