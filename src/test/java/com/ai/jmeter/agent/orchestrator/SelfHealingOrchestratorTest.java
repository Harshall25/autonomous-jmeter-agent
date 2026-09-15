package com.ai.jmeter.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.AgentRunOutcome;
import com.ai.jmeter.agent.domain.AgentRunRequest;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.domain.ExecutionStatus;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.SampleFailure;
import com.ai.jmeter.agent.domain.SelfHealingFailedException;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.domain.jmx.JmxMutation;
import com.ai.jmeter.agent.domain.jmx.JmxRepairPlan;
import com.ai.jmeter.agent.domain.jmx.JmxStructure;
import com.ai.jmeter.agent.domain.jmx.JmxValidationResult;
import com.ai.jmeter.agent.domain.redaction.CompliancePolicyViolationException;
import com.ai.jmeter.agent.domain.redaction.RedactedSecret;
import com.ai.jmeter.agent.domain.redaction.RedactionResult;
import com.ai.jmeter.agent.domain.redaction.SecretCategory;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.JmxDocumentException;
import com.ai.jmeter.agent.port.JmxDocumentPort;
import com.ai.jmeter.agent.port.SensitiveDataRedactorPort;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * Drives the agentic loop against mocked ports.
 *
 * <p>These are the tests that pin the agent's central promises: nothing leaves the host
 * unredacted, a plan that cannot work never costs an execution, a failing run always produces
 * another repair turn, and the retry budget is genuinely bounded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SelfHealingOrchestrator")
class SelfHealingOrchestratorTest {

    private static final Path SOURCE = Path.of("capture.har");
    private static final String RAW_TRAFFIC = "[{\"url\":\"/v1/login\",\"token\":\"live\"}]";
    private static final String SAFE_TRAFFIC = "[{\"url\":\"/v1/login\",\"token\":\"REDACTED\"}]";

    private static final JmeterGenerationResult FIRST_DRAFT =
            new JmeterGenerationResult("<plan>draft</plan>", "user\nalice", List.of("user"), "v1");
    private static final JmeterGenerationResult REWRITTEN =
            new JmeterGenerationResult("<plan>rewritten</plan>", "user\nalice", List.of("user"), "v2");

    private static final WorkspaceArtifacts ARTIFACTS = new WorkspaceArtifacts(
            Path.of("workspace/auto_test.jmx"), Path.of("workspace/test_data.csv"));

    private static final ExecutionReport UNAUTHORIZED = ExecutionReport.sampleFailures(
            1, List.of(new SampleFailure("login", "401", "Unauthorized", "Token was empty")), "");
    private static final ExecutionReport CLEAN_RUN = ExecutionReport.success(4, "");

    private static final JmxRepairPlan MUTATION_REPAIR = new JmxRepairPlan(
            List.of(new JmxMutation.AddJsonPathExtractor(
                    "login", "auth_token", "$.access_token", "NOT_FOUND")),
            "Login response was never mined for the bearer token",
            false);

    @Mock
    private TrafficParserPort harParser;

    @Mock
    private JmeterAgentPort agent;

    @Mock
    private ExecutionEnginePort executionEngine;

    @Mock
    private WorkspacePort workspace;

    @Mock
    private SensitiveDataRedactorPort redactor;

    @Mock
    private JmxDocumentPort jmxDocument;

    @BeforeEach
    void setUp() {
        when(harParser.supportedMode()).thenReturn(ExecutionMode.API);
        when(harParser.parse(SOURCE)).thenReturn(RAW_TRAFFIC);
        when(redactor.redact(anyString())).thenReturn(RedactionResult.clean(SAFE_TRAFFIC));
        when(workspace.write(any())).thenReturn(ARTIFACTS);
        when(agent.generateScript(anyString(), any())).thenReturn(FIRST_DRAFT);
        when(agent.healScript(anyString(), anyString())).thenReturn(REWRITTEN);
        when(agent.proposeRepairs(anyString(), anyString())).thenReturn(MUTATION_REPAIR);
        when(jmxDocument.validate(anyString())).thenReturn(JmxValidationResult.valid());
        when(jmxDocument.describe(anyString())).thenReturn(
                new JmxStructure(List.of("login"), List.of("user"), List.of("user")));
        when(jmxDocument.apply(anyString(), anyList())).thenReturn("<plan>patched</plan>");
    }

    private SelfHealingOrchestrator orchestrator(int maxAttempts, boolean strictCompliance) {
        return new SelfHealingOrchestrator(
                new TrafficParserRegistry(List.of(harParser)),
                agent, executionEngine, workspace, redactor, jmxDocument,
                maxAttempts, strictCompliance);
    }

    private AgentRunOutcome run(int maxAttempts) {
        return orchestrator(maxAttempts, false)
                .run(new AgentRunRequest(ExecutionMode.API, SOURCE));
    }

    @Nested
    @DisplayName("the agentic loop")
    class Loop {

        @Test
        @DisplayName("Case 1: a plan that passes first time ends the loop immediately")
        void terminatesOnFirstSuccess() {
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            AgentRunOutcome outcome = run(3);

            assertThat(outcome.attempts()).isEqualTo(1);
            assertThat(outcome.healedFirstTime()).isTrue();
            assertThat(outcome.script()).isSameAs(FIRST_DRAFT);
            assertThat(outcome.report()).isSameAs(CLEAN_RUN);
            assertThat(outcome.artifacts()).isSameAs(ARTIFACTS);

            verify(agent).generateScript(SAFE_TRAFFIC, ExecutionMode.API);
            verify(agent, never()).proposeRepairs(anyString(), anyString());
            verify(agent, never()).healScript(anyString(), anyString());
            verify(executionEngine, times(1)).execute(ARTIFACTS.jmxScript());
        }

        @Test
        @DisplayName("Case 2: a 401 triggers exactly one repair turn, and the retry passes")
        void healsThenSucceeds() {
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

            AgentRunOutcome outcome = run(3);

            assertThat(outcome.attempts()).isEqualTo(2);
            assertThat(outcome.healedFirstTime()).isFalse();
            assertThat(outcome.script().jmxXmlContent())
                    .as("the outcome carries the patched plan, not the draft that failed")
                    .isEqualTo("<plan>patched</plan>");

            verify(agent, times(1)).generateScript(anyString(), any());
            verify(agent, times(1)).proposeRepairs(anyString(), anyString());
            verify(executionEngine, times(2)).execute(any());
            verify(workspace, times(2)).write(any());
        }

        @Test
        @DisplayName("Case 2: the repair turn receives the plan structure and the run evidence")
        void repairReceivesStructureAndEvidence() {
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

            run(3);

            ArgumentCaptor<String> structure = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
            verify(agent).proposeRepairs(structure.capture(), evidence.capture());

            assertThat(structure.getValue()).contains("Samplers: [login]");
            assertThat(evidence.getValue())
                    .contains("SAMPLE_FAILURE")
                    .contains("responseCode=401")
                    .contains("Token was empty");
        }

        @Test
        @DisplayName("Case 3: the retry budget is bounded and exhausting it is an error")
        void failsAfterMaxAttempts() {
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED);

            assertThatExceptionOfType(SelfHealingFailedException.class)
                    .isThrownBy(() -> run(3))
                    .satisfies(thrown -> {
                        assertThat(thrown.attempts()).isEqualTo(3);
                        assertThat(thrown.lastReport()).isSameAs(UNAUTHORIZED);
                    });

            verify(executionEngine, times(3)).execute(any());
            verify(agent, times(2)).proposeRepairs(anyString(), anyString());
        }

        @Test
        @DisplayName("Case 3: a budget of one means no repair turn at all")
        void singleAttemptBudgetNeverHeals() {
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED);

            assertThatExceptionOfType(SelfHealingFailedException.class).isThrownBy(() -> run(1));

            verify(executionEngine, times(1)).execute(any());
            verify(agent, never()).proposeRepairs(anyString(), anyString());
        }

        @Test
        @DisplayName("rejects a retry budget that would let no run happen at all")
        void rejectsNonPositiveBudget() {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> orchestrator(0, false))
                    .withMessageContaining("maxAttempts must be at least 1");
        }

        @Test
        @DisplayName("runs whatever plan the workspace actually wrote")
        void executesTheWrittenArtifact() {
            WorkspaceArtifacts elsewhere =
                    new WorkspaceArtifacts(Path.of("/tmp/other.jmx"), Path.of("/tmp/other.csv"));
            when(workspace.write(any())).thenReturn(elsewhere);
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            run(3);

            verify(executionEngine).execute(eq(Path.of("/tmp/other.jmx")));
        }

        @Test
        @DisplayName("lets a parsing failure surface instead of prompting the model with nothing")
        void parsingFailureAbortsBeforeAnyModelCall() {
            when(harParser.parse(SOURCE)).thenThrow(new IllegalStateException("corrupt capture"));

            assertThatExceptionOfType(IllegalStateException.class)
                    .isThrownBy(() -> run(3))
                    .withMessage("corrupt capture");

            verifyNoInteractions(agent, executionEngine);
        }
    }

    @Nested
    @DisplayName("pre-flight validation")
    class PreFlight {

        @Test
        @DisplayName("skips execution entirely for a plan that cannot work")
        void invalidPlanIsNeverExecuted() {
            when(jmxDocument.validate(anyString()))
                    .thenReturn(JmxValidationResult.error("Plan contains no samplers"))
                    .thenReturn(JmxValidationResult.valid());
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            AgentRunOutcome outcome = run(3);

            assertThat(outcome.attempts()).isEqualTo(2);
            verify(executionEngine, times(1))
                    .execute(any());
            verify(workspace, times(1))
                    .write(any());
        }

        @Test
        @DisplayName("feeds the validator's findings to the repair turn as run evidence")
        void validationFindingsDriveTheRepair() {
            when(jmxDocument.validate(anyString()))
                    .thenReturn(JmxValidationResult.error("Plan contains no samplers"))
                    .thenReturn(JmxValidationResult.valid());
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            run(3);

            ArgumentCaptor<String> evidence = ArgumentCaptor.forClass(String.class);
            verify(agent).proposeRepairs(anyString(), evidence.capture());
            assertThat(evidence.getValue())
                    .contains("VALIDATION_FAILURE")
                    .contains("Plan contains no samplers");
        }

        @Test
        @DisplayName("gives up on a plan that never becomes valid")
        void permanentlyInvalidPlanExhaustsTheBudget() {
            when(jmxDocument.validate(anyString()))
                    .thenReturn(JmxValidationResult.error("Plan is not well-formed XML"));

            assertThatExceptionOfType(SelfHealingFailedException.class)
                    .isThrownBy(() -> run(2))
                    .satisfies(thrown -> assertThat(thrown.lastReport().status())
                            .isEqualTo(ExecutionStatus.VALIDATION_FAILURE));

            verifyNoInteractions(executionEngine);
        }

        @Test
        @DisplayName("proceeds when validation only raises warnings")
        void warningsDoNotBlockExecution() {
            when(jmxDocument.validate(anyString())).thenReturn(new JmxValidationResult(
                    List.of(), List.of("Variable ${token} is referenced but never defined")));
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            assertThat(run(3).attempts()).isEqualTo(1);
            verify(executionEngine).execute(any());
        }
    }

    @Nested
    @DisplayName("structured repair")
    class StructuredRepair {

        @Test
        @DisplayName("applies the model's edits rather than regenerating the plan")
        void appliesMutations() {
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

            run(3);

            verify(jmxDocument).apply(eq("<plan>draft</plan>"), eq(MUTATION_REPAIR.mutations()));
            verify(agent, never()).healScript(anyString(), anyString());
        }

        @Test
        @DisplayName("carries the CSV and variables through a patch untouched")
        void patchPreservesTestData() {
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

            AgentRunOutcome outcome = run(3);

            assertThat(outcome.script().csvTemplateContent()).isEqualTo("user\nalice");
            assertThat(outcome.script().identifiedVariables()).containsExactly("user");
            assertThat(outcome.script().executionRationale())
                    .isEqualTo("Login response was never mined for the bearer token");
        }

        @Test
        @DisplayName("regenerates the plan when the model asks for a rewrite")
        void fallsBackToRewriteOnRequest() {
            when(agent.proposeRepairs(anyString(), anyString()))
                    .thenReturn(JmxRepairPlan.rewrite("Plan is structurally beyond patching"));
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

            AgentRunOutcome outcome = run(3);

            assertThat(outcome.script()).isSameAs(REWRITTEN);
            verify(agent).healScript(eq("<plan>draft</plan>"), anyString());
            verify(jmxDocument, never()).apply(anyString(), anyList());
        }

        @Test
        @DisplayName("treats an empty edit list as a request to rewrite")
        void emptyMutationListFallsBack() {
            when(agent.proposeRepairs(anyString(), anyString()))
                    .thenReturn(new JmxRepairPlan(List.of(), "nothing to change", false));
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

            assertThat(run(3).script()).isSameAs(REWRITTEN);
        }

        @Test
        @DisplayName("regenerates the plan when an edit cannot be applied to it")
        void fallsBackWhenMutationCannotBeApplied() {
            when(jmxDocument.apply(anyString(), anyList()))
                    .thenThrow(new JmxDocumentException("No element named 'login'"));
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

            AgentRunOutcome outcome = run(3);

            assertThat(outcome.script()).isSameAs(REWRITTEN);
            verify(agent).healScript(anyString(), anyString());
        }

        @Test
        @DisplayName("tells the model the plan is unparseable when it cannot be summarized")
        void unparseablePlanIsReportedToTheModel() {
            when(jmxDocument.describe(anyString()))
                    .thenThrow(new JmxDocumentException("mismatched tag at line 4"));
            when(executionEngine.execute(any())).thenReturn(UNAUTHORIZED, CLEAN_RUN);

            run(3);

            ArgumentCaptor<String> structure = ArgumentCaptor.forClass(String.class);
            verify(agent).proposeRepairs(structure.capture(), anyString());
            assertThat(structure.getValue())
                    .contains("Plan could not be parsed")
                    .contains("mismatched tag at line 4");
        }
    }

    @Nested
    @DisplayName("redaction and compliance")
    class Redaction {

        private RedactionResult withCredential() {
            return new RedactionResult(SAFE_TRAFFIC, List.of(new RedactedSecret(
                    SecretCategory.CREDENTIAL,
                    "${__P(agent.secret.credential.1)}",
                    "eyJhbGciOiJIUzI1NiJ9.live.token")));
        }

        @Test
        @DisplayName("redacts the capture before the model ever sees it")
        void redactsBeforeGeneration() {
            when(redactor.redact(anyString())).thenReturn(withCredential());
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            run(3);

            verify(redactor).redact(RAW_TRAFFIC);
            ArgumentCaptor<String> sentToModel = ArgumentCaptor.forClass(String.class);
            verify(agent).generateScript(sentToModel.capture(), any());
            assertThat(sentToModel.getValue())
                    .isEqualTo(SAFE_TRAFFIC)
                    .doesNotContain("live.token");
        }

        @Test
        @DisplayName("persists the withheld values for JMeter to bind at run time")
        void writesSecretBindings() {
            when(redactor.redact(anyString())).thenReturn(withCredential());
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            run(3);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, String>> bindings = ArgumentCaptor.forClass(Map.class);
            verify(workspace).writeSecretBindings(bindings.capture());
            assertThat(bindings.getValue())
                    .containsEntry("agent.secret.credential.1", "eyJhbGciOiJIUzI1NiJ9.live.token");
        }

        @Test
        @DisplayName("keeps the compliance record on the run outcome")
        void recordsRedactionOnOutcome() {
            when(redactor.redact(anyString())).thenReturn(withCredential());
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            AgentRunOutcome outcome = run(3);

            assertThat(outcome.redaction().foundSecrets()).isTrue();
            assertThat(outcome.redaction().countsByCategory())
                    .containsEntry(SecretCategory.CREDENTIAL, 1L);
        }

        @Test
        @DisplayName("refuses a regulated capture outright under strict compliance")
        void strictComplianceRefusesRegulatedCaptures() {
            when(redactor.redact(anyString())).thenReturn(withCredential());
            SelfHealingOrchestrator strict = orchestrator(3, true);
            AgentRunRequest request = new AgentRunRequest(ExecutionMode.API, SOURCE);

            assertThatExceptionOfType(CompliancePolicyViolationException.class)
                    .isThrownBy(() -> strict.run(request))
                    .satisfies(thrown -> assertThat(thrown.violations())
                            .containsExactly(SecretCategory.CREDENTIAL));

            verifyNoInteractions(agent, executionEngine);
        }

        @Test
        @DisplayName("allows a clean capture through under strict compliance")
        void strictComplianceAllowsCleanCaptures() {
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            AgentRunOutcome outcome = orchestrator(3, true)
                    .run(new AgentRunRequest(ExecutionMode.API, SOURCE));

            assertThat(outcome.redaction().foundSecrets()).isFalse();
        }
    }

    @Nested
    @DisplayName("SQL mode")
    class SqlMode {

        private TrafficParserPort sqlParser() {
            TrafficParserPort parser = mock(TrafficParserPort.class);
            when(parser.supportedMode()).thenReturn(ExecutionMode.SQL);
            when(parser.parse(SOURCE)).thenReturn("SELECT 1;");
            return parser;
        }

        private AgentRunOutcome runSqlMode(TrafficParserPort parser) {
            return new SelfHealingOrchestrator(
                    new TrafficParserRegistry(List.of(parser)),
                    agent, executionEngine, workspace, redactor, jmxDocument, 3, false)
                    .run(new AgentRunRequest(ExecutionMode.SQL, SOURCE));
        }

        @Test
        @DisplayName("checks for a JDBC driver before spending the budget")
        void checksDriverWhenMissing() {
            when(workspace.jdbcDriverAvailable()).thenReturn(false);
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            runSqlMode(sqlParser());

            verify(workspace).jdbcDriverAvailable();
            verify(agent).generateScript(SAFE_TRAFFIC, ExecutionMode.SQL);
        }

        @Test
        @DisplayName("proceeds quietly when the JDBC driver is present")
        void proceedsWhenDriverPresent() {
            when(workspace.jdbcDriverAvailable()).thenReturn(true);
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            assertThat(runSqlMode(sqlParser()).report().status())
                    .isEqualTo(ExecutionStatus.SUCCESS);
        }

        @Test
        @DisplayName("never asks about JDBC drivers in API mode")
        void skipsDriverCheckInApiMode() {
            when(executionEngine.execute(any())).thenReturn(CLEAN_RUN);

            run(3);

            verify(workspace, never()).jdbcDriverAvailable();
        }
    }
}
