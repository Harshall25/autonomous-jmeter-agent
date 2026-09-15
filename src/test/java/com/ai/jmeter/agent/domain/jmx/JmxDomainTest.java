package com.ai.jmeter.agent.domain.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Covers the pure-Java mutation vocabulary and the values that describe a plan. */
class JmxDomainTest {

    @Nested
    @DisplayName("JmxMutation descriptions")
    class Descriptions {

        @Test
        @DisplayName("every edit renders a one-line audit entry")
        void everyMutationDescribesItself() {
            List<JmxMutation> mutations = List.of(
                    new JmxMutation.AddJsonPathExtractor("login", "token", "$.token", "NONE"),
                    new JmxMutation.AddRegexExtractor(
                            "login", "session", "S=(.+?);", "$1$", "NONE", true),
                    new JmxMutation.SetHeader("orders", "Authorization", "Bearer x"),
                    new JmxMutation.AddCsvDataSet("test_data.csv", List.of("user")),
                    new JmxMutation.ConfigureThreadGroup(50, 30, 10),
                    new JmxMutation.ReplaceLiteralWithVariable("alice", "username"),
                    new JmxMutation.RemoveElement("flaky"));

            assertThat(mutations).allSatisfy(
                    mutation -> assertThat(mutation.describe()).isNotBlank());
        }

        @Test
        @DisplayName("an extractor names its variable, source and expression")
        void jsonExtractorDescription() {
            assertThat(new JmxMutation.AddJsonPathExtractor(
                    "login", "token", "$.access_token", "NONE").describe())
                    .isEqualTo("extract ${token} from login via JSONPath $.access_token");
        }

        @Test
        @DisplayName("a regex extractor says whether it reads headers or the body")
        void regexExtractorDescription() {
            assertThat(new JmxMutation.AddRegexExtractor(
                    "login", "s", "S=(.+?);", "$1$", "NONE", true).describe())
                    .contains("(headers)");
            assertThat(new JmxMutation.AddRegexExtractor(
                    "login", "s", "S=(.+?);", "$1$", "NONE", false).describe())
                    .contains("(body)");
        }

        @Test
        @DisplayName("a header edit says what it is scoped to")
        void headerDescription() {
            assertThat(new JmxMutation.SetHeader("orders", "Accept", "*/*").describe())
                    .isEqualTo("set header Accept on orders");
            assertThat(new JmxMutation.SetHeader("", "Accept", "*/*").describe())
                    .isEqualTo("set header Accept on the whole plan");
        }

        @Test
        @DisplayName("a header edit knows when it is plan-wide")
        void headerScope() {
            assertThat(new JmxMutation.SetHeader("orders", "A", "b").isPlanWide()).isFalse();
            assertThat(new JmxMutation.SetHeader("", "A", "b").isPlanWide()).isTrue();
            assertThat(new JmxMutation.SetHeader(null, "A", "b").isPlanWide()).isTrue();
            assertThat(new JmxMutation.SetHeader("  ", "A", "b").isPlanWide()).isTrue();
        }

        @Test
        @DisplayName("a CSV edit defends its variable list against later mutation")
        void csvCopiesVariableList() {
            List<String> mutable = new java.util.ArrayList<>(List.of("user"));
            JmxMutation.AddCsvDataSet csv = new JmxMutation.AddCsvDataSet("f.csv", mutable);

            mutable.add("injected");

            assertThat(csv.variableNames()).containsExactly("user");
            assertThat(csv.describe()).contains("f.csv").contains("[user]");
        }

        @Test
        @DisplayName("a workload edit renders its shape")
        void workloadDescription() {
            assertThat(new JmxMutation.ConfigureThreadGroup(50, 30, 10).describe())
                    .isEqualTo("set workload to 50 thread(s), 30s ramp-up, 10 loop(s)");
        }

        @Test
        @DisplayName("a parameterization and a removal render their target")
        void parameterizeAndRemoveDescriptions() {
            assertThat(new JmxMutation.ReplaceLiteralWithVariable("alice", "username").describe())
                    .isEqualTo("parameterize literal into ${username}");
            assertThat(new JmxMutation.RemoveElement("flaky").describe())
                    .isEqualTo("remove element flaky");
        }
    }

    @Nested
    @DisplayName("JmxRepairPlan")
    class RepairPlan {

        private final JmxMutation edit =
                new JmxMutation.RemoveElement("flaky");

        @Test
        @DisplayName("a plan with edits is applied, not rewritten")
        void editsAreApplied() {
            JmxRepairPlan plan = new JmxRepairPlan(List.of(edit), "diagnosis", false);

            assertThat(plan.requiresFullRewrite()).isFalse();
            assertThat(plan.describe()).contains("remove element flaky");
        }

        @Test
        @DisplayName("an empty edit list means a rewrite, or the loop would spin")
        void emptyEditsMeanRewrite() {
            assertThat(new JmxRepairPlan(List.of(), "d", false).requiresFullRewrite()).isTrue();
        }

        @Test
        @DisplayName("an explicit rewrite flag wins over the edits attached")
        void explicitRewriteWins() {
            assertThat(new JmxRepairPlan(List.of(edit), "d", true).requiresFullRewrite()).isTrue();
        }

        @Test
        @DisplayName("normalizes a missing edit list and diagnosis")
        void normalizesNulls() {
            JmxRepairPlan plan = new JmxRepairPlan(null, null, false);

            assertThat(plan.mutations()).isEmpty();
            assertThat(plan.diagnosis()).isEmpty();
            assertThat(plan.describe()).isEmpty();
        }

        @Test
        @DisplayName("the rewrite factory carries the diagnosis through")
        void rewriteFactory() {
            JmxRepairPlan plan = JmxRepairPlan.rewrite("beyond patching");

            assertThat(plan.requiresFullRewrite()).isTrue();
            assertThat(plan.diagnosis()).isEqualTo("beyond patching");
        }
    }

    @Nested
    @DisplayName("JmxValidationResult")
    class Validation {

        @Test
        @DisplayName("a clean result is valid and silent")
        void validResult() {
            JmxValidationResult result = JmxValidationResult.valid();

            assertThat(result.isValid()).isTrue();
            assertThat(result.hasWarnings()).isFalse();
            assertThat(result.describe()).isEmpty();
        }

        @Test
        @DisplayName("an error makes the result invalid")
        void errorResult() {
            JmxValidationResult result = JmxValidationResult.error("no samplers");

            assertThat(result.isValid()).isFalse();
            assertThat(result.describe()).isEqualTo("ERROR: no samplers");
        }

        @Test
        @DisplayName("a warning leaves the plan runnable but flags it")
        void warningResult() {
            JmxValidationResult result =
                    new JmxValidationResult(List.of(), List.of("unresolved ${token}"));

            assertThat(result.isValid()).isTrue();
            assertThat(result.hasWarnings()).isTrue();
            assertThat(result.describe()).isEqualTo("WARNING: unresolved ${token}");
        }

        @Test
        @DisplayName("renders errors ahead of warnings")
        void rendersErrorsFirst() {
            JmxValidationResult result =
                    new JmxValidationResult(List.of("broken"), List.of("suspicious"));

            assertThat(result.describe()).isEqualTo("ERROR: broken\nWARNING: suspicious");
        }
    }

    @Nested
    @DisplayName("JmxStructure")
    class Structure {

        @Test
        @DisplayName("flags a reference nothing defines")
        void flagsUnresolvedReferences() {
            JmxStructure structure = new JmxStructure(
                    List.of("login"), List.of("username"), List.of("username", "auth_token"));

            assertThat(structure.unresolvedVariables()).containsExactly("auth_token");
        }

        @Test
        @DisplayName("reports nothing unresolved when every reference is defined")
        void noUnresolvedReferences() {
            JmxStructure structure = new JmxStructure(
                    List.of("login"), List.of("username"), List.of("username"));

            assertThat(structure.unresolvedVariables()).isEmpty();
        }

        @Test
        @DisplayName("renders a briefing the model can act on")
        void rendersBriefing() {
            JmxStructure structure = new JmxStructure(
                    List.of("login"), List.of("username"), List.of("auth_token"));

            assertThat(structure.describe())
                    .contains("Samplers: [login]")
                    .contains("Defined variables: [username]")
                    .contains("Referenced variables: [auth_token]")
                    .contains("Unresolved references: [auth_token]");
        }
    }
}
