package com.ai.jmeter.agent.adapter.workload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.workload.WorkloadModel;
import com.ai.jmeter.agent.port.TrafficParsingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("AccessLogWorkloadProfiler")
class AccessLogWorkloadProfilerTest {

    private final AccessLogWorkloadProfiler profiler = new AccessLogWorkloadProfiler(30);

    @TempDir
    Path tempDir;

    private Path writeLog(String content) throws IOException {
        Path file = tempDir.resolve("access.log");
        Files.writeString(file, content);
        return file;
    }

    /** Combined Log Format lines spread over consecutive seconds. */
    private static String combinedLog(int requestCount, int requestsPerSecond, String path) {
        return IntStream.range(0, requestCount)
                .mapToObj(index -> """
                        10.0.0.%d - - [11/Mar/2026:08:%02d:%02d +0000] \
                        "GET %s HTTP/1.1" 200 1234 "-" "Mozilla/5.0\""""
                        .formatted(index % 250, 12, index / requestsPerSecond, path))
                .collect(Collectors.joining("\n"));
    }

    @Test
    @DisplayName("infers a shape from a log with enough traffic to be credible")
    void infersCredibleShape() throws IOException {
        WorkloadModel model = profiler.profile(writeLog(combinedLog(60, 10, "/v1/orders")));

        assertThat(model.isCredible()).isTrue();
        assertThat(model.peakRequestsPerSecond()).isEqualTo(10);
        assertThat(model.rampUpSeconds()).isEqualTo(30);
        assertThat(model.observedWindowSeconds()).isEqualTo(6);
    }

    @Test
    @DisplayName("sizes concurrency from peak arrival rate and think time")
    void sizesConcurrencyFromLittlesLaw() throws IOException {
        // Little's Law: 10 arrivals/second at a 1-second think time needs 10 users in the system.
        WorkloadModel model = profiler.profile(writeLog(combinedLog(60, 10, "/v1/orders")));

        assertThat(model.concurrentUsers()).isEqualTo(10);
    }

    @Test
    @DisplayName("takes the busiest second rather than the average")
    void usesPeakNotMean() throws IOException {
        // Capacity is sized against the worst second a system must survive; averaging a log
        // flattens exactly the spike that matters.
        String spiky = """
                10.0.0.1 - - [11/Mar/2026:08:12:59 +0000] "GET /v1/a HTTP/1.1" 200 1 "-" "ua"
                """
                + combinedLog(40, 20, "/v1/spike");

        WorkloadModel model = profiler.profile(writeLog(spiky));

        assertThat(model.peakRequestsPerSecond())
                .as("two busy seconds and one quiet one: the peak is 20, the mean is under 14")
                .isEqualTo(20);
    }

    @Test
    @DisplayName("reports the endpoint mix so the plan weights calls correctly")
    void reportsEndpointMix() throws IOException {
        String mixed = combinedLog(40, 10, "/v1/read") + "\n" + combinedLog(20, 10, "/v1/write");

        WorkloadModel model = profiler.profile(writeLog(mixed));

        assertThat(model.endpointMix()).containsKeys("GET /v1/read", "GET /v1/write");
        assertThat(model.endpointMix().get("GET /v1/read")).isCloseTo(2.0 / 3, within());
        assertThat(model.busiestEndpoints(1))
                .singleElement()
                .satisfies(entry -> assertThat(entry.getKey()).isEqualTo("GET /v1/read"));
    }

    @Test
    @DisplayName("drops the query string so endpoint variants collapse")
    void dropsQueryStrings() throws IOException {
        String withQueries = IntStream.range(0, 30)
                .mapToObj(index -> """
                        10.0.0.1 - - [11/Mar/2026:08:12:%02d +0000] \
                        "GET /v1/orders?page=%d HTTP/1.1" 200 1 "-" "ua\""""
                        .formatted(index % 10, index))
                .collect(Collectors.joining("\n"));

        WorkloadModel model = profiler.profile(writeLog(withQueries));

        assertThat(model.endpointMix()).containsOnlyKeys("GET /v1/orders");
    }

    @Test
    @DisplayName("falls back to a smoke test when the sample is too small to mean anything")
    void tooLittleTrafficFallsBack() throws IOException {
        // A handful of lines is one person clicking around; inferring a workload from it would
        // dress a guess up as a measurement.
        WorkloadModel model = profiler.profile(writeLog(combinedLog(5, 1, "/v1/orders")));

        assertThat(model.isCredible()).isFalse();
        assertThat(model.concurrentUsers()).isEqualTo(1);
        assertThat(model.describe()).contains("single-user smoke test");
    }

    @Test
    @DisplayName("ignores lines that are not access log records")
    void ignoresUnparseableLines() throws IOException {
        String noisy = "### rotated at 08:00\n"
                + combinedLog(30, 10, "/v1/orders")
                + "\nnot a log line at all\n";

        assertThat(profiler.profile(writeLog(noisy)).isCredible()).isTrue();
    }

    @Test
    @DisplayName("ignores a record whose timestamp cannot be read")
    void ignoresUnparseableTimestamps() throws IOException {
        String noisy = combinedLog(30, 10, "/v1/orders")
                + "\n10.0.0.1 - - [not-a-timestamp] \"GET /v1/x HTTP/1.1\" 200 1 \"-\" \"ua\"\n";

        WorkloadModel model = profiler.profile(writeLog(noisy));

        assertThat(model.endpointMix()).containsOnlyKeys("GET /v1/orders");
    }

    @Test
    @DisplayName("defaults think time when every request shares a timestamp")
    void defaultsThinkTimeForCoarseLogs() throws IOException {
        String sameSecond = IntStream.range(0, 30)
                .mapToObj(index -> """
                        10.0.0.1 - - [11/Mar/2026:08:12:00 +0000] \
                        "GET /v1/orders HTTP/1.1" 200 1 "-" "ua\"""")
                .collect(Collectors.joining("\n"));

        WorkloadModel model = profiler.profile(writeLog(sameSecond));

        assertThat(model.averageThinkTimeMillis()).isEqualTo(1000);
        assertThat(model.concurrentUsers()).isEqualTo(30);
    }

    @Test
    @DisplayName("renders a shape an operator can sanity-check")
    void describesTheShape() throws IOException {
        String description = profiler.profile(writeLog(combinedLog(60, 10, "/v1/orders")))
                .describe();

        assertThat(description)
                .contains("Peak throughput")
                .contains("Concurrent users")
                .contains("Ramp-up")
                .contains("GET /v1/orders");
    }

    @Test
    @DisplayName("reports an unreadable telemetry file")
    void rejectsUnreadableFile() {
        Path missing = tempDir.resolve("absent.log");

        assertThatThrownBy(() -> profiler.profile(missing))
                .isInstanceOf(TrafficParsingException.class)
                .hasMessageContaining("Unable to read access log");
    }

    @Test
    @DisplayName("a smoke-test shape is never treated as credible")
    void smokeTestIsNotCredible() {
        assertThat(WorkloadModel.smokeTest().isCredible()).isFalse();
        assertThat(WorkloadModel.smokeTest().busiestEndpoints(5)).isEmpty();
    }

    @Test
    @DisplayName("a mix with no observation window is not credible either")
    void windowlessShapeIsNotCredible() {
        WorkloadModel windowless = new WorkloadModel(
                10, 5, 30, 1000, java.util.Map.of("GET /v1/a", 1.0), 0);

        assertThat(windowless.isCredible()).isFalse();
    }

    private static org.assertj.core.data.Offset<Double> within() {
        return org.assertj.core.data.Offset.offset(0.01);
    }
}
