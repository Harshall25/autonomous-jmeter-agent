package com.ai.jmeter.agent.adapter.ci;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.ci.GateVerdict;
import com.ai.jmeter.agent.port.BuildReportException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("GitHubActionsBuildReporter")
class GitHubActionsBuildReporterTest {

    @TempDir
    Path workspace;

    private ByteArrayOutputStream captured;
    private PrintStream console;
    private Path reportFile;

    @BeforeEach
    void setUp() {
        captured = new ByteArrayOutputStream();
        console = new PrintStream(captured, true, StandardCharsets.UTF_8);
        reportFile = workspace.resolve("reports/performance-report.md");
    }

    private String consoleOutput() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private GitHubActionsBuildReporter reporter(Path stepSummary) {
        return new GitHubActionsBuildReporter(reportFile, stepSummary, console);
    }

    @Nested
    @DisplayName("the report file a pipeline step posts")
    class ReportFile {

        @Test
        @DisplayName("writes the comment where a workflow step can pick it up")
        void writesTheComment() throws IOException {
            reporter(null).publish(GateVerdict.allowed(), "### Performance gate passed");

            assertThat(Files.readString(reportFile)).contains("### Performance gate passed");
        }

        @Test
        @DisplayName("replaces the previous run's report rather than appending to it")
        void overwritesAStaleReport() throws IOException {
            reporter(null).publish(GateVerdict.allowed(), "first run");
            reporter(null).publish(GateVerdict.allowed(), "second run");

            assertThat(Files.readString(reportFile))
                    .contains("second run")
                    .doesNotContain("first run");
        }

        @Test
        @DisplayName("reports a location it cannot write to instead of failing silently")
        void reportsAnUnwritableLocation() throws IOException {
            Path blocked = workspace.resolve("blocked");
            Files.writeString(blocked, "not a directory");
            GitHubActionsBuildReporter brittle = new GitHubActionsBuildReporter(
                    blocked.resolve("nested/report.md"), null, console);
            GateVerdict verdict = GateVerdict.allowed();

            assertThatThrownBy(() -> brittle.publish(verdict, "anything"))
                    .isInstanceOf(BuildReportException.class)
                    .hasMessageContaining("Unable to publish the performance report");
        }
    }

    @Nested
    @DisplayName("the GitHub Actions step summary")
    class StepSummary {

        @Test
        @DisplayName("appends the report to the summary, alongside other steps' output")
        void appendsToTheSummary() throws IOException {
            Path summary = workspace.resolve("step-summary.md");
            Files.writeString(summary, "## Build\n");

            reporter(summary).publish(GateVerdict.allowed(), "## Performance");

            assertThat(Files.readString(summary))
                    .contains("## Build")
                    .contains("## Performance");
        }

        @Test
        @DisplayName("writes no summary when the run is not on GitHub Actions")
        void skipsTheSummaryOffPlatform() {
            reporter(null).publish(GateVerdict.allowed(), "## Performance");

            assertThat(workspace.resolve("step-summary.md")).doesNotExist();
        }
    }

    @Nested
    @DisplayName("check annotations")
    class Annotations {

        @Test
        @DisplayName("annotates a passing run once, so the check says so without a log dive")
        void annotatesAPass() {
            reporter(null).publish(GateVerdict.allowed(), "report");

            assertThat(consoleOutput())
                    .isEqualTo("::notice title=Performance gate::"
                            + "No performance regressions detected" + System.lineSeparator());
        }

        @Test
        @DisplayName("raises one error annotation per breach")
        void annotatesEachBreach() {
            reporter(null).publish(
                    GateVerdict.blocked(List.of("checkout regressed", "search regressed")),
                    "report");

            assertThat(consoleOutput().lines()).containsExactly(
                    "::error title=Performance gate::checkout regressed",
                    "::error title=Performance gate::search regressed");
        }

        @Test
        @DisplayName("escapes a reason that would otherwise truncate or split the annotation")
        void escapesWorkflowCommandSyntax() {
            // Workflow commands are newline-delimited and use :: as their own delimiter, so a
            // multi-line reason would silently lose everything after the first line.
            reporter(null).publish(
                    GateVerdict.blocked(List.of("checkout: p95 900ms\nwas 300ms (300% over)")),
                    "report");

            assertThat(consoleOutput().lines()).containsExactly(
                    "::error title=Performance gate::checkout: p95 900ms%0Awas 300ms (300%25 over)");
        }

        @Test
        @DisplayName("escapes a carriage return and a nested command delimiter")
        void escapesCarriageReturnsAndDelimiters() {
            reporter(null).publish(GateVerdict.blocked(List.of("one\rtwo::three")), "report");

            assertThat(consoleOutput().lines())
                    .containsExactly("::error title=Performance gate::one%0Dtwo%3A%3Athree");
        }
    }
}
