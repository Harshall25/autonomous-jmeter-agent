package com.ai.jmeter.agent.adapter.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.jmx.JmxMutation;
import com.ai.jmeter.agent.domain.jmx.JmxRepairPlan;
import com.ai.jmeter.agent.port.JmeterAgentException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Covers the narrowing of the model's flat, permissive reply into the sealed domain hierarchy —
 * the point where an unreliable generator meets a type system that will not tolerate ambiguity.
 */
@DisplayName("RepairPlanResponse")
class RepairPlanResponseTest {

    /** Builds a command with only the fields a given edit kind needs. */
    private static RepairPlanResponse.MutationCommand command(String type) {
        return new RepairPlanResponse.MutationCommand(
                type, null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
    }

    private static JmxRepairPlan planOf(RepairPlanResponse.MutationCommand command) {
        return new RepairPlanResponse("diagnosis", false, List.of(command)).toDomain();
    }

    @Nested
    @DisplayName("mapping each edit kind")
    class Mapping {

        @Test
        @DisplayName("maps a JSON extractor")
        void mapsJsonPathExtractor() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "jsonPathExtractor", "login", "auth_token", "$.access_token",
                    null, null, "NONE", null, null, null, null, null,
                    null, null, null, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.AddJsonPathExtractor(
                            "login", "auth_token", "$.access_token", "NONE"));
        }

        @Test
        @DisplayName("defaults the extractor's fallback value when the model omits it")
        void defaultsExtractorFallback() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "jsonPathExtractor", "login", "token", "$.token",
                    null, null, null, null, null, null, null, null,
                    null, null, null, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.AddJsonPathExtractor("login", "token", "$.token", "NOT_FOUND"));
        }

        @Test
        @DisplayName("treats a blank optional field as absent and uses the default")
        void blankOptionalFieldFallsBackToDefault() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "jsonPathExtractor", "login", "token", "$.token",
                    null, null, "   ", null, null, null, null, null,
                    null, null, null, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.AddJsonPathExtractor("login", "token", "$.token", "NOT_FOUND"));
        }

        @Test
        @DisplayName("maps a regex extractor, defaulting template and header scope")
        void mapsRegexExtractor() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "regexExtractor", "login", "session", null, "SESSION=(.+?);",
                    null, null, null, null, null, null, null,
                    null, null, null, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.AddRegexExtractor(
                            "login", "session", "SESSION=(.+?);", "$1$", "NOT_FOUND", false));
        }

        @Test
        @DisplayName("honours an explicit header scope on a regex extractor")
        void honoursHeaderScope() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "regexExtractor", "login", "session", null, "SESSION=(.+?);", "$2$",
                    "NONE", true, null, null, null, null, null, null, null, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.AddRegexExtractor(
                            "login", "session", "SESSION=(.+?);", "$2$", "NONE", true));
        }

        @Test
        @DisplayName("maps a header edit")
        void mapsSetHeader() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "setHeader", "orders", null, null, null, null, null, null,
                    "Authorization", "Bearer ${auth_token}", null, null,
                    null, null, null, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.SetHeader("orders", "Authorization", "Bearer ${auth_token}"));
        }

        @Test
        @DisplayName("maps a plan-wide header when no sampler is named")
        void mapsPlanWideHeader() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "setHeader", null, null, null, null, null, null, null,
                    "X-Trace", null, null, null, null, null, null, null, null);

            JmxMutation.SetHeader mutation =
                    (JmxMutation.SetHeader) planOf(command).mutations().get(0);

            assertThat(mutation.isPlanWide()).isTrue();
            assertThat(mutation.headerValue()).isEmpty();
        }

        @Test
        @DisplayName("maps a CSV data set, defaulting the filename")
        void mapsCsvDataSet() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "csvDataSet", null, null, null, null, null, null, null, null, null,
                    null, List.of("username", "password"), null, null, null, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.AddCsvDataSet(
                            "test_data.csv", List.of("username", "password")));
        }

        @Test
        @DisplayName("tolerates a CSV data set with no variables listed")
        void mapsCsvDataSetWithoutVariables() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "csvDataSet", null, null, null, null, null, null, null, null, null,
                    "feed.csv", null, null, null, null, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.AddCsvDataSet("feed.csv", List.of()));
        }

        @Test
        @DisplayName("maps a workload change")
        void mapsThreadGroup() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "threadGroup", null, null, null, null, null, null, null, null, null,
                    null, null, 50, 30, 10, null, null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.ConfigureThreadGroup(50, 30, 10));
        }

        @Test
        @DisplayName("defaults a workload change to a single-thread smoke test")
        void defaultsThreadGroup() {
            assertThat(planOf(command("threadGroup")).mutations()).containsExactly(
                    new JmxMutation.ConfigureThreadGroup(1, 1, 1));
        }

        @Test
        @DisplayName("maps a parameterization")
        void mapsParameterize() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "parameterize", null, "username", null, null, null, null, null, null, null,
                    null, null, null, null, null, "alice@corp.test", null);

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.ReplaceLiteralWithVariable("alice@corp.test", "username"));
        }

        @Test
        @DisplayName("maps a removal")
        void mapsRemove() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "remove", null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, "flaky-sampler");

            assertThat(planOf(command).mutations()).containsExactly(
                    new JmxMutation.RemoveElement("flaky-sampler"));
        }

        @Test
        @DisplayName("accepts whatever casing the model used for the edit kind")
        void acceptsAnyCasing() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "REMOVE", null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, "sampler");

            assertThat(planOf(command).mutations()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("rejecting an unusable proposal")
    class Rejection {

        @Test
        @DisplayName("rejects an edit kind that does not exist")
        void rejectsUnknownType() {
            assertThatThrownBy(() -> planOf(command("teleport")))
                    .isInstanceOf(JmeterAgentException.class)
                    .hasMessageContaining("unknown mutation type 'teleport'");
        }

        @Test
        @DisplayName("rejects an edit with no kind at all")
        void rejectsMissingType() {
            assertThatThrownBy(() -> planOf(command(null)))
                    .isInstanceOf(JmeterAgentException.class)
                    .hasMessageContaining("missing required field 'type'");
        }

        @Test
        @DisplayName("names the field an edit is missing")
        void rejectsMissingRequiredField() {
            assertThatThrownBy(() -> planOf(command("jsonPathExtractor")))
                    .isInstanceOf(JmeterAgentException.class)
                    .hasMessageContaining("'jsonpathextractor'")
                    .hasMessageContaining("'samplerName'");
        }

        @Test
        @DisplayName("rejects a regex extractor with no expression")
        void rejectsRegexWithoutExpression() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "regexExtractor", "login", "session", null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null);

            assertThatThrownBy(() -> planOf(command))
                    .isInstanceOf(JmeterAgentException.class)
                    .hasMessageContaining("'regex'");
        }

        @Test
        @DisplayName("rejects a header edit with no header name")
        void rejectsHeaderWithoutName() {
            assertThatThrownBy(() -> planOf(command("setHeader")))
                    .isInstanceOf(JmeterAgentException.class)
                    .hasMessageContaining("'headerName'");
        }

        @Test
        @DisplayName("rejects a parameterization with no literal")
        void rejectsParameterizeWithoutLiteral() {
            assertThatThrownBy(() -> planOf(command("parameterize")))
                    .isInstanceOf(JmeterAgentException.class)
                    .hasMessageContaining("'literal'");
        }

        @Test
        @DisplayName("rejects a removal with no target")
        void rejectsRemoveWithoutTarget() {
            assertThatThrownBy(() -> planOf(command("remove")))
                    .isInstanceOf(JmeterAgentException.class)
                    .hasMessageContaining("'testName'");
        }

        @Test
        @DisplayName("treats a blank field as absent")
        void blankFieldIsMissing() {
            RepairPlanResponse.MutationCommand command = new RepairPlanResponse.MutationCommand(
                    "remove", null, null, null, null, null, null, null, null, null,
                    null, null, null, null, null, null, "   ");

            assertThatThrownBy(() -> planOf(command))
                    .isInstanceOf(JmeterAgentException.class)
                    .hasMessageContaining("'testName'");
        }
    }

    @Nested
    @DisplayName("falling back to a rewrite")
    class Fallback {

        @Test
        @DisplayName("treats an empty edit list as a rewrite request")
        void emptyMutationsMeansRewrite() {
            JmxRepairPlan plan = new RepairPlanResponse("no idea", false, List.of()).toDomain();

            assertThat(plan.requiresFullRewrite()).isTrue();
            assertThat(plan.diagnosis()).isEqualTo("no idea");
        }

        @Test
        @DisplayName("treats a missing edit list as a rewrite request")
        void nullMutationsMeansRewrite() {
            assertThat(new RepairPlanResponse("no idea", false, null).toDomain()
                    .requiresFullRewrite()).isTrue();
        }

        @Test
        @DisplayName("honours an explicit rewrite request even with edits attached")
        void explicitRewriteWins() {
            JmxRepairPlan plan = new RepairPlanResponse(
                    "plan is structurally broken", true,
                    List.of(new RepairPlanResponse.MutationCommand(
                            "remove", null, null, null, null, null, null, null, null, null,
                            null, null, null, null, null, null, "x"))).toDomain();

            assertThat(plan.requiresFullRewrite()).isTrue();
            assertThat(plan.mutations()).hasSize(1);
        }
    }
}
