package com.ai.jmeter.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import com.ai.jmeter.agent.domain.cost.RunCost;
import com.ai.jmeter.agent.domain.results.RunSummary;
import java.time.Instant;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** Covers the small pure-Java value objects that carry state through the agentic workflow. */
class DomainValueObjectsTest {

    @Nested
    @DisplayName("ExecutionMode")
    class ExecutionModeTest {

        @Test
        @DisplayName("only SQL mode needs a JDBC driver on the JMeter classpath")
        void jdbcDriverRequirement() {
            assertThat(ExecutionMode.SQL.requiresJdbcDriver()).isTrue();
            assertThat(ExecutionMode.API.requiresJdbcDriver()).isFalse();
        }

        @ParameterizedTest
        @EnumSource(ExecutionMode.class)
        @DisplayName("every mode describes itself for operator-facing output")
        void describesItself(ExecutionMode mode) {
            assertThat(mode.description()).isNotBlank();
            assertThat(ExecutionMode.valueOf(mode.name())).isSameAs(mode);
        }
    }

    @Nested
    @DisplayName("ExecutionStatus")
    class ExecutionStatusTest {

        @Test
        @DisplayName("SUCCESS is the only status that terminates the loop")
        void onlySuccessTerminates() {
            assertThat(ExecutionStatus.SUCCESS.successful()).isTrue();
            assertThat(ExecutionStatus.SAMPLE_FAILURE.successful()).isFalse();
            assertThat(ExecutionStatus.PROCESS_FAILURE.successful()).isFalse();
            assertThat(ExecutionStatus.NO_SAMPLES.successful()).isFalse();
            assertThat(ExecutionStatus.VALIDATION_FAILURE.successful()).isFalse();
            assertThat(ExecutionStatus.valueOf("SUCCESS")).isSameAs(ExecutionStatus.SUCCESS);
        }
    }

    @Nested
    @DisplayName("SampleFailure")
    class SampleFailureTest {

        @Test
        @DisplayName("includes the assertion text when JMeter recorded one")
        void describesWithFailureMessage() {
            SampleFailure failure =
                    new SampleFailure("login", "401", "Unauthorized", "Expected 200");

            assertThat(failure.describe())
                    .isEqualTo("sampler='login' responseCode=401 "
                            + "responseMessage='Unauthorized' failureMessage='Expected 200'");
        }

        @Test
        @DisplayName("omits the assertion text when there was none")
        void describesWithoutFailureMessage() {
            SampleFailure failure = new SampleFailure("login", "500", "Server Error", "");

            assertThat(failure.describe())
                    .isEqualTo("sampler='login' responseCode=500 responseMessage='Server Error'")
                    .doesNotContain("failureMessage");
        }

        @Test
        @DisplayName("normalizes nulls so describe is always safe")
        void normalizesNulls() {
            SampleFailure failure = new SampleFailure(null, null, null, null);

            assertThat(failure.label()).isEmpty();
            assertThat(failure.responseCode()).isEmpty();
            assertThat(failure.responseMessage()).isEmpty();
            assertThat(failure.failureMessage()).isEmpty();
            assertThat(failure.describe()).isNotBlank();
        }
    }

    @Nested
    @DisplayName("AgentRunRequest")
    class AgentRunRequestTest {

        @Test
        @DisplayName("holds the mode and source together")
        void holdsFields() {
            AgentRunRequest request = AgentRunRequest.of(ExecutionMode.API, Path.of("a.har"));

            assertThat(request.mode()).isEqualTo(ExecutionMode.API);
            assertThat(request.sourceFile()).isEqualTo(Path.of("a.har"));
        }

        @Test
        @DisplayName("rejects a missing mode")
        void rejectsNullMode() {
            Path source = Path.of("a.har");
            assertThatNullPointerException()
                    .isThrownBy(() -> new AgentRunRequest(null, source, null))
                    .withMessageContaining("mode");
        }

        @Test
        @DisplayName("rejects a missing source file")
        void rejectsNullSource() {
            assertThatNullPointerException()
                    .isThrownBy(() -> new AgentRunRequest(ExecutionMode.SQL, null, null))
                    .withMessageContaining("sourceFile");
        }
    }

    @Nested
    @DisplayName("AgentRunOutcome")
    class AgentRunOutcomeTest {

        private RunSummary summary() {
            return new RunSummary("run-1", "fp", Instant.EPOCH, java.util.Map.of(), 1, 0);
        }

        private AgentRunOutcome outcomeAfter(int attempts) {
            return new AgentRunOutcome(
                    ExecutionMode.API,
                    new JmeterGenerationResult("<plan/>", "", List.of(), ""),
                    new WorkspaceArtifacts(Path.of("auto_test.jmx"), Path.of("test_data.csv")),
                    ExecutionReport.success(1, ""),
                    attempts,
                    RedactionResult.clean("[]"),
                    RunCost.empty(0),
                    RunAnalysis.withoutComparison(summary()));
        }

        @Test
        @DisplayName("reports a first-time pass when no healing was needed")
        void firstTimePass() {
            assertThat(outcomeAfter(1).healedFirstTime()).isTrue();
        }

        @Test
        @DisplayName("reports a healed pass when repairs were needed")
        void healedPass() {
            AgentRunOutcome outcome = outcomeAfter(3);

            assertThat(outcome.healedFirstTime()).isFalse();
            assertThat(outcome.attempts()).isEqualTo(3);
            assertThat(outcome.mode()).isEqualTo(ExecutionMode.API);
            assertThat(outcome.artifacts().jmxScript()).isEqualTo(Path.of("auto_test.jmx"));
            assertThat(outcome.artifacts().csvData()).isEqualTo(Path.of("test_data.csv"));
            assertThat(outcome.script().jmxXmlContent()).isEqualTo("<plan/>");
            assertThat(outcome.report().successful()).isTrue();
        }
    }

    @Nested
    @DisplayName("SelfHealingFailedException")
    class SelfHealingFailedExceptionTest {

        @Test
        @DisplayName("carries the full diagnostic trail to the operator")
        void carriesDiagnostics() {
            ExecutionReport report = ExecutionReport.sampleFailures(
                    2,
                    List.of(new SampleFailure("login", "401", "Unauthorized", "")),
                    "console output");

            SelfHealingFailedException exception = new SelfHealingFailedException(3, report);

            assertThat(exception.attempts()).isEqualTo(3);
            assertThat(exception.lastReport()).isSameAs(report);
            assertThat(exception.getMessage())
                    .contains("after 3 attempt(s)")
                    .contains("SAMPLE_FAILURE")
                    .contains("sampler='login'");
        }
    }
}
