package com.ai.jmeter.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("ExecutionReport")
class ExecutionReportTest {

    private static SampleFailure failure(String label) {
        return new SampleFailure(label, "401", "Unauthorized", "Token was empty");
    }

    @Nested
    @DisplayName("factories")
    class Factories {

        @Test
        @DisplayName("success marks the run clean")
        void success() {
            ExecutionReport report = ExecutionReport.success(12, "ok");

            assertThat(report.successful()).isTrue();
            assertThat(report.status()).isEqualTo(ExecutionStatus.SUCCESS);
            assertThat(report.totalSamples()).isEqualTo(12);
            assertThat(report.failedSamples()).isZero();
        }

        @Test
        @DisplayName("sampleFailures records what failed")
        void sampleFailures() {
            ExecutionReport report =
                    ExecutionReport.sampleFailures(5, List.of(failure("login")), "output");

            assertThat(report.successful()).isFalse();
            assertThat(report.status()).isEqualTo(ExecutionStatus.SAMPLE_FAILURE);
            assertThat(report.failedSamples()).isEqualTo(1);
        }

        @Test
        @DisplayName("processFailure records a run that never produced results")
        void processFailure() {
            ExecutionReport report = ExecutionReport.processFailure("exit 1");

            assertThat(report.status()).isEqualTo(ExecutionStatus.PROCESS_FAILURE);
            assertThat(report.totalSamples()).isZero();
            assertThat(report.successful()).isFalse();
        }

        @Test
        @DisplayName("noSamples records a run that exercised nothing")
        void noSamples() {
            ExecutionReport report = ExecutionReport.noSamples("nothing ran");

            assertThat(report.status()).isEqualTo(ExecutionStatus.NO_SAMPLES);
            assertThat(report.successful()).isFalse();
        }
    }

    @Nested
    @DisplayName("normalization")
    class Normalization {

        @Test
        @DisplayName("defaults null collaborators so the digest is always renderable")
        void defaultsNulls() {
            ExecutionReport report =
                    new ExecutionReport(ExecutionStatus.PROCESS_FAILURE, 0, null, null);

            assertThat(report.failures()).isEmpty();
            assertThat(report.processOutput()).isEmpty();
            assertThat(report.errorDigest()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("errorDigest")
    class ErrorDigest {

        @Test
        @DisplayName("states the headline numbers for a clean run")
        void cleanRun() {
            String digest = ExecutionReport.success(3, "").errorDigest();

            assertThat(digest)
                    .contains("Execution status: SUCCESS")
                    .contains("Total samples: 3")
                    .contains("Failed samples: 0")
                    .doesNotContain("Failure details")
                    .doesNotContain("JMeter process output");
        }

        @Test
        @DisplayName("lists each failure so the model can diagnose the cause")
        void listsFailures() {
            ExecutionReport report = ExecutionReport.sampleFailures(
                    2, List.of(failure("login"), failure("checkout")), "console noise");

            String digest = report.errorDigest();

            assertThat(digest)
                    .contains("Failure details:")
                    .contains("sampler='login'")
                    .contains("sampler='checkout'")
                    .contains("responseCode=401")
                    .contains("JMeter process output:")
                    .contains("console noise");
        }

        @Test
        @DisplayName("truncates a flood of failures rather than blowing the context window")
        void truncatesLargeFailureLists() {
            List<SampleFailure> many = IntStream.rangeClosed(1, 40)
                    .mapToObj(index -> failure("sampler-" + index))
                    .toList();

            String digest = ExecutionReport.sampleFailures(40, many, "").errorDigest();

            assertThat(digest)
                    .contains("Failed samples: 40")
                    .contains("sampler-25")
                    .doesNotContain("sampler-26")
                    .contains("... 15 further failures omitted");
        }

        @Test
        @DisplayName("keeps every failure when the list fits under the cap")
        void keepsSmallFailureLists() {
            List<SampleFailure> exactlyAtLimit = IntStream.rangeClosed(1, 25)
                    .mapToObj(index -> failure("sampler-" + index))
                    .toList();

            String digest = ExecutionReport.sampleFailures(25, exactlyAtLimit, "").errorDigest();

            assertThat(digest)
                    .contains("sampler-25")
                    .doesNotContain("further failures omitted");
        }
    }
}
