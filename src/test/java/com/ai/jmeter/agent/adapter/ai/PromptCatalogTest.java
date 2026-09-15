package com.ai.jmeter.agent.adapter.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PromptCatalog")
class PromptCatalogTest {

    private final PromptCatalog catalog = TestFixtures.promptCatalog();

    @Test
    @DisplayName("briefs the model on correlation and parameterization in API mode")
    void apiSystemPrompt() {
        String prompt = catalog.systemPromptFor(ExecutionMode.API);

        assertThat(prompt)
                .contains("Expert Performance Engineer")
                .contains("Auto-Correlate")
                .contains("Auto-Parameterize")
                .contains("CSV Data Set Config")
                .contains("JSON Schema");
    }

    @Test
    @DisplayName("briefs the model on JDBC samplers in SQL mode")
    void sqlSystemPrompt() {
        String prompt = catalog.systemPromptFor(ExecutionMode.SQL);

        assertThat(prompt)
                .contains("Expert Database Performance Engineer")
                .contains("JDBC Request Sampler")
                .contains("JDBC Connection Configuration")
                .contains("WHERE clauses");
    }

    @Test
    @DisplayName("passes JMeter's ${...} syntax to the model untouched")
    void preservesJmeterVariableSyntax() {
        // The StringTemplate renderer treats braces as placeholders. If these prompts were
        // rendered rather than read, ${variable_name} would be mangled or rejected outright,
        // and the model would never be told what JMeter variable syntax looks like.
        assertThat(catalog.systemPromptFor(ExecutionMode.API)).contains("${variable_name}");
        assertThat(catalog.systemPromptFor(ExecutionMode.SQL)).contains("${variable_name}");
    }

    @Test
    @DisplayName("interpolates the failed plan and the run evidence into the healing brief")
    void healPromptInterpolatesEvidence() {
        String prompt = catalog.healSystemPrompt("<jmeterTestPlan>broken</jmeterTestPlan>",
                "401 Unauthorized on /v1/orders");

        assertThat(prompt)
                .contains("<jmeterTestPlan>broken</jmeterTestPlan>")
                .contains("401 Unauthorized on /v1/orders")
                .doesNotContain("{current_script}")
                .doesNotContain("{error_logs}");
    }

    @Test
    @DisplayName("interpolates a plan that itself contains JMeter variable syntax")
    void healPromptHandlesVariableSyntaxInScript() {
        // The failed plan is full of ${...} references. Substituted values must not be
        // re-parsed as template placeholders, or every healing turn would blow up.
        String plan = "<stringProp name=\"Argument.value\">${auth_token}</stringProp>";

        String prompt = catalog.healSystemPrompt(plan, "empty ${auth_token}");

        assertThat(prompt).contains("${auth_token}").contains("empty ${auth_token}");
    }

    @Test
    @DisplayName("asks for the deliverable in the accompanying user turn")
    void healInstruction() {
        assertThat(catalog.healInstruction()).contains("corrected JMeter test plan");
    }

    @Test
    @DisplayName("interpolates the plan structure and evidence into the repair brief")
    void repairPromptInterpolatesStructure() {
        String prompt = catalog.repairSystemPrompt(
                "Samplers: [login, orders]", "401 Unauthorized on /v1/orders");

        assertThat(prompt)
                .contains("Samplers: [login, orders]")
                .contains("401 Unauthorized on /v1/orders")
                .contains("jsonPathExtractor")
                .doesNotContain("{plan_structure}")
                .doesNotContain("{error_logs}");
    }

    @Test
    @DisplayName("asks for edits in the accompanying user turn")
    void repairInstruction() {
        assertThat(catalog.repairInstruction()).contains("smallest set of structural edits");
    }
}
