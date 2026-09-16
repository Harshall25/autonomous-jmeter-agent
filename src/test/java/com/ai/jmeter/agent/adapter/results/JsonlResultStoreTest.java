package com.ai.jmeter.agent.adapter.results;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import com.ai.jmeter.agent.port.ResultStoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("JsonlResultStore")
class JsonlResultStoreTest {

    @TempDir
    Path tempDir;

    private Path store;
    private JsonlResultStore results;

    @BeforeEach
    void setUp() {
        store = tempDir.resolve("history/run-history.jsonl");
        results = new JsonlResultStore(new ObjectMapper(), store);
    }

    private static RunSummary summary(String runId, String fingerprint, Instant at, long p95) {
        return new RunSummary(runId, fingerprint, at,
                Map.of("login", new SampleStatistics("login", 100, 2, 42.5, 40, p95, p95 + 10, 900)),
                10, 55.5);
    }

    @Test
    @DisplayName("creates the history file on first record")
    void createsStore() {
        results.record(summary("run-1", "plan-a", Instant.EPOCH, 100));

        assertThat(store).exists();
    }

    @Test
    @DisplayName("round-trips a summary through disk without losing precision")
    void roundTripsSummary() {
        Instant at = Instant.parse("2026-03-11T08:12:04Z");
        results.record(summary("run-1", "plan-a", at, 100));

        RunSummary restored = results.history("plan-a", 10).get(0);

        assertThat(restored.runId()).isEqualTo("run-1");
        assertThat(restored.recordedAt()).isEqualTo(at);
        assertThat(restored.concurrentUsers()).isEqualTo(10);
        assertThat(restored.throughputPerSecond()).isEqualTo(55.5);
        assertThat(restored.statisticsByLabel().get("login"))
                .isEqualTo(new SampleStatistics("login", 100, 2, 42.5, 40, 100, 110, 900));
    }

    @Test
    @DisplayName("keeps runs of different plans in separate series")
    void separatesPlans() {
        // Comparing latency across different workloads would produce alerts nobody can act on.
        results.record(summary("run-1", "plan-a", Instant.EPOCH, 100));
        results.record(summary("run-2", "plan-b", Instant.EPOCH, 999));

        assertThat(results.history("plan-a", 10))
                .singleElement()
                .satisfies(run -> assertThat(run.runId()).isEqualTo("run-1"));
    }

    @Test
    @DisplayName("returns the most recent runs first")
    void ordersMostRecentFirst() {
        results.record(summary("older", "plan-a", Instant.parse("2026-03-01T00:00:00Z"), 100));
        results.record(summary("newer", "plan-a", Instant.parse("2026-03-09T00:00:00Z"), 120));

        assertThat(results.history("plan-a", 10))
                .extracting(RunSummary::runId)
                .containsExactly("newer", "older");
    }

    @Test
    @DisplayName("honours the requested depth")
    void honoursLimit() {
        for (int index = 0; index < 5; index++) {
            results.record(summary("run-" + index, "plan-a",
                    Instant.EPOCH.plusSeconds(index), 100));
        }

        assertThat(results.history("plan-a", 2)).hasSize(2);
    }

    @Test
    @DisplayName("reports no history for a plan that has never run")
    void emptyHistoryForUnknownPlan() {
        assertThat(results.history("never-run", 10)).isEmpty();
    }

    @Test
    @DisplayName("reports no history before anything has been recorded")
    void emptyStore() {
        assertThat(results.history("plan-a", 10)).isEmpty();
    }

    @Test
    @DisplayName("ignores blank lines in the store")
    void ignoresBlankLines() throws IOException {
        results.record(summary("run-1", "plan-a", Instant.EPOCH, 100));
        Files.writeString(store, "\n\n", StandardOpenOption.APPEND);

        assertThat(results.history("plan-a", 10)).hasSize(1);
    }

    @Test
    @DisplayName("degrades to whatever parsed cleanly when the store is corrupt")
    void toleratesCorruptStore() throws IOException {
        // One bad append must not destroy a history that took weeks to accumulate.
        results.record(summary("run-1", "plan-a", Instant.EPOCH, 100));
        Files.writeString(store, "{not json\n", StandardOpenOption.APPEND);

        assertThat(results.history("plan-a", 10)).hasSize(1);
    }

    @Test
    @DisplayName("reports a store that cannot be written to")
    void reportsUnwritableStore() throws IOException {
        Path blocked = tempDir.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        JsonlResultStore brittle = new JsonlResultStore(
                new ObjectMapper(), blocked.resolve("nested/run-history.jsonl"));
        RunSummary run = summary("run-1", "plan-a", Instant.EPOCH, 100);

        assertThatThrownBy(() -> brittle.record(run))
                .isInstanceOf(ResultStoreException.class)
                .hasMessageContaining("Unable to record run results");
    }

    @Test
    @DisplayName("summarizes a run for an operator")
    void describesARun() {
        RunSummary run = summary("run-1", "plan-a", Instant.EPOCH, 100);

        assertThat(run.describe())
                .contains("Run run-1")
                .contains("Concurrency : 10 user(s)")
                .contains("Samples     : 100 (2 failed)")
                .contains("login");
        assertThat(run.totalSamples()).isEqualTo(100);
        assertThat(run.totalFailures()).isEqualTo(2);
    }

    @Test
    @DisplayName("ranks the slowest samplers, which is where tuning effort belongs")
    void ranksSlowestSamplers() {
        RunSummary run = new RunSummary("run", "plan", Instant.EPOCH,
                Map.of(
                        "fast", new SampleStatistics("fast", 10, 0, 5, 5, 10, 12, 20),
                        "slow", new SampleStatistics("slow", 10, 0, 500, 500, 900, 950, 1200)),
                1, 1);

        assertThat(run.slowestSamplers(1))
                .singleElement()
                .satisfies(statistics -> assertThat(statistics.label()).isEqualTo("slow"));
        assertThat(run.slowestSamplers(5)).hasSize(2);
    }

    @Test
    @DisplayName("groups runs of the same endpoints under one fingerprint")
    void fingerprintIsStableAcrossRuns() {
        String first = com.ai.jmeter.agent.domain.results.PlanFingerprint.of(
                com.ai.jmeter.agent.domain.ExecutionMode.API, List.of("login", "orders"));
        String reordered = com.ai.jmeter.agent.domain.results.PlanFingerprint.of(
                com.ai.jmeter.agent.domain.ExecutionMode.API, List.of("orders", "login", "login"));

        assertThat(first)
                .as("the agent rewrites the XML every healing turn; the endpoints are what is stable")
                .isEqualTo(reordered);
    }

    @Test
    @DisplayName("separates fingerprints when the workload itself changes")
    void fingerprintChangesWithWorkload() {
        String twoEndpoints = com.ai.jmeter.agent.domain.results.PlanFingerprint.of(
                com.ai.jmeter.agent.domain.ExecutionMode.API, List.of("login", "orders"));
        String threeEndpoints = com.ai.jmeter.agent.domain.results.PlanFingerprint.of(
                com.ai.jmeter.agent.domain.ExecutionMode.API, List.of("login", "orders", "cart"));
        String differentMode = com.ai.jmeter.agent.domain.results.PlanFingerprint.of(
                com.ai.jmeter.agent.domain.ExecutionMode.SQL, List.of("login", "orders"));

        assertThat(twoEndpoints).isNotEqualTo(threeEndpoints).isNotEqualTo(differentMode);
    }
}
