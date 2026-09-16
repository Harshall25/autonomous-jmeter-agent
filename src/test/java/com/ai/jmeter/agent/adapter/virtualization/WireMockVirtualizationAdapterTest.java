package com.ai.jmeter.agent.adapter.virtualization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.virtualization.StubbedDependency;
import com.ai.jmeter.agent.domain.virtualization.VirtualizedEnvironment;
import com.ai.jmeter.agent.port.VirtualizationException;
import com.fasterxml.jackson.databind.JsonNode;
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

@DisplayName("WireMockVirtualizationAdapter")
class WireMockVirtualizationAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path workspace;

    private WireMockVirtualizationAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new WireMockVirtualizationAdapter(
                objectMapper, workspace, "wiremock/wiremock:3.9.1", 8089);
    }

    private JsonNode mappingsOf(VirtualizedEnvironment environment, String name)
            throws IOException {
        return objectMapper.readTree(
                environment.mappingsDirectory().resolve(name + ".json").toFile());
    }

    @Nested
    @DisplayName("stub generation")
    class StubGeneration {

        @Test
        @DisplayName("writes one mapping file per dependency")
        void writesAMappingPerDependency() {
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    StubbedDependency.alwaysHealthy(
                            "payments", "/v1/charge.*", "POST", "{\"ok\":true}"),
                    StubbedDependency.alwaysHealthy(
                            "inventory", "/v1/stock.*", "GET", "{\"count\":5}")));

            assertThat(environment.mappingsDirectory().resolve("payments.json")).exists();
            assertThat(environment.mappingsDirectory().resolve("inventory.json")).exists();
        }

        @Test
        @DisplayName("stubs the method, path and body the dependency should answer with")
        void writesRequestAndResponse() throws IOException {
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    StubbedDependency.alwaysHealthy(
                            "payments", "/v1/charge.*", "post", "{\"ok\":true}")));

            JsonNode mapping = mappingsOf(environment, "payments").path("mappings").get(0);

            assertThat(mapping.path("request").path("method").asText()).isEqualTo("POST");
            assertThat(mapping.path("request").path("urlPattern").asText())
                    .isEqualTo("/v1/charge.*");
            assertThat(mapping.path("response").path("status").asInt()).isEqualTo(200);
            assertThat(mapping.path("response").path("body").asText()).contains("ok");
        }

        @Test
        @DisplayName("makes latency a controlled variable rather than an observed one")
        void appliesConfiguredLatency() throws IOException {
            // A dependency that can be told to take 300ms lets a team measure how their system
            // behaves when it does, before it does.
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    new StubbedDependency(
                            "slow-partner", "/v1/quote.*", "GET", 200, "{}", 300, 0)));

            assertThat(mappingsOf(environment, "slow-partner")
                    .path("mappings").get(0)
                    .path("response").path("fixedDelayMilliseconds").asLong())
                    .isEqualTo(300);
        }

        @Test
        @DisplayName("omits the delay setting when no latency was configured")
        void omitsDelayWhenZero() throws IOException {
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    StubbedDependency.alwaysHealthy("fast", "/v1/ping", "GET", "{}")));

            assertThat(mappingsOf(environment, "fast").path("mappings").get(0)
                    .path("response").has("fixedDelayMilliseconds")).isFalse();
        }

        @Test
        @DisplayName("writes a failing variant alongside the healthy one when failures are injected")
        void injectsFailures() throws IOException {
            // A single mapping cannot express "fails sometimes", and testing against a dependency
            // that never fails proves nothing about what happens when it does.
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    new StubbedDependency("flaky", "/v1/quote.*", "GET", 200, "{}", 0, 20)));

            JsonNode mappings = mappingsOf(environment, "flaky").path("mappings");

            assertThat(mappings).hasSize(2);
            assertThat(mappings.get(0).path("metadata").path("weightPercentage").asInt())
                    .isEqualTo(80);
            assertThat(mappings.get(1).path("response").path("status").asInt()).isEqualTo(500);
            assertThat(mappings.get(1).path("metadata").path("weightPercentage").asInt())
                    .isEqualTo(20);
        }

        @Test
        @DisplayName("writes one mapping when the dependency never fails")
        void singleMappingWhenHealthy() throws IOException {
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    StubbedDependency.alwaysHealthy("steady", "/v1/ping", "GET", "{}")));

            assertThat(mappingsOf(environment, "steady").path("mappings")).hasSize(1);
        }

        @Test
        @DisplayName("sanitizes a dependency name that would not make a filename")
        void sanitizesFilenames() {
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    StubbedDependency.alwaysHealthy(
                            "payments/v1 (eu)", "/v1/charge", "GET", "{}")));

            assertThat(environment.mappingsDirectory().resolve("payments-v1--eu-.json")).exists();
        }

        @Test
        @DisplayName("writes a compose file that runs the stubs")
        void writesComposeFile() throws IOException {
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    StubbedDependency.alwaysHealthy("payments", "/v1/charge", "GET", "{}")));

            assertThat(Files.readString(environment.composeFile()))
                    .contains("wiremock/wiremock:3.9.1")
                    .contains("8089:8080")
                    .contains("./mappings:/home/wiremock/mappings:ro");
        }

        @Test
        @DisplayName("reports a workspace the stubs cannot be written to")
        void reportsUnwritableWorkspace() throws IOException {
            Path blocked = workspace.resolve("blocked");
            Files.writeString(blocked, "not a directory");
            WireMockVirtualizationAdapter brittle = new WireMockVirtualizationAdapter(
                    objectMapper, blocked.resolve("nested"), "wiremock", 8089);
            List<StubbedDependency> dependencies = List.of(
                    StubbedDependency.alwaysHealthy("payments", "/v1/charge", "GET", "{}"));

            assertThatThrownBy(() -> brittle.virtualize(dependencies))
                    .isInstanceOf(VirtualizationException.class)
                    .hasMessageContaining("Unable to write service virtualization stubs");
        }
    }

    @Nested
    @DisplayName("the environment it produces")
    class Environment {

        @Test
        @DisplayName("says where the plan should point instead of the real dependency")
        void reportsBaseUrl() {
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    StubbedDependency.alwaysHealthy("payments", "/v1/charge", "GET", "{}")));

            assertThat(environment.baseUrl()).isEqualTo("http://localhost:8089");
        }

        @Test
        @DisplayName("summarizes what was stubbed and how it will behave")
        void describesItself() {
            VirtualizedEnvironment environment = adapter.virtualize(List.of(
                    new StubbedDependency("flaky", "/v1/quote.*", "GET", 200, "{}", 300, 20)));

            assertThat(environment.describe())
                    .contains("Virtualized 1 dependency(ies)")
                    .contains("http://localhost:8089")
                    .contains("flaky: GET /v1/quote.* -> 200 after 300ms, 20% failing");
        }
    }

    @Nested
    @DisplayName("dependency declarations")
    class Declarations {

        @Test
        @DisplayName("normalizes a missing method and body")
        void normalizesDefaults() {
            StubbedDependency dependency =
                    new StubbedDependency("x", "/v1/x", null, 200, null, 0, 0);

            assertThat(dependency.method()).isEqualTo("GET");
            assertThat(dependency.responseBody()).isEmpty();
        }

        @Test
        @DisplayName("treats a blank method as unspecified")
        void blankMethodDefaults() {
            assertThat(new StubbedDependency("x", "/v1/x", "  ", 200, "{}", 0, 0).method())
                    .isEqualTo("GET");
        }

        @Test
        @DisplayName("clamps nonsensical latency and failure rates")
        void clampsOutOfRangeValues() {
            StubbedDependency dependency =
                    new StubbedDependency("x", "/v1/x", "GET", 200, "{}", -50, 400);

            assertThat(dependency.fixedDelayMillis()).isZero();
            assertThat(dependency.failurePercentage()).isEqualTo(100);
            assertThat(new StubbedDependency("x", "/v1/x", "GET", 200, "{}", 0, -5)
                    .failurePercentage()).isZero();
        }

        @Test
        @DisplayName("knows whether it injects failures")
        void reportsFailureInjection() {
            assertThat(StubbedDependency.alwaysHealthy("x", "/v1/x", "GET", "{}")
                    .injectsFailures()).isFalse();
            assertThat(new StubbedDependency("x", "/v1/x", "GET", 200, "{}", 0, 1)
                    .injectsFailures()).isTrue();
        }

        @Test
        @DisplayName("describes a healthy dependency without latency or failure noise")
        void describesHealthyDependency() {
            assertThat(StubbedDependency.alwaysHealthy("payments", "/v1/charge", "POST", "{}")
                    .describe())
                    .isEqualTo("payments: POST /v1/charge -> 200");
        }
    }
}
