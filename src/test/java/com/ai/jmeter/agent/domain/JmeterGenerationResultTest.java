package com.ai.jmeter.agent.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("JmeterGenerationResult")
class JmeterGenerationResultTest {

    private static final String JMX = "<jmeterTestPlan/>";

    @Test
    @DisplayName("keeps a well-formed payload untouched")
    void retainsCompletePayload() {
        JmeterGenerationResult result = new JmeterGenerationResult(
                JMX, "user,pass\na,b", List.of("user", "pass"), "Correlated the bearer token");

        assertThat(result.jmxXmlContent()).isEqualTo(JMX);
        assertThat(result.csvTemplateContent()).isEqualTo("user,pass\na,b");
        assertThat(result.identifiedVariables()).containsExactly("user", "pass");
        assertThat(result.executionRationale()).isEqualTo("Correlated the bearer token");
    }

    @ParameterizedTest(name = "rejects jmxXmlContent = [{0}]")
    @ValueSource(strings = {"", "   ", "\n\t"})
    @DisplayName("rejects a blank plan, which would be unusable downstream")
    void rejectsBlankJmx(String blank) {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new JmeterGenerationResult(blank, "", List.of(), ""))
                .withMessageContaining("jmxXmlContent");
    }

    @Test
    @DisplayName("rejects a null plan")
    void rejectsNullJmx() {
        assertThatThrownBy(() -> new JmeterGenerationResult(null, "", List.of(), ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jmxXmlContent");
    }

    @Test
    @DisplayName("defaults the optional text payloads so callers never null-check")
    void defaultsOptionalText() {
        JmeterGenerationResult result = new JmeterGenerationResult(JMX, null, null, null);

        assertThat(result.csvTemplateContent()).isEmpty();
        assertThat(result.executionRationale()).isEmpty();
        assertThat(result.identifiedVariables()).isEmpty();
    }

    @Test
    @DisplayName("drops null entries a lenient model reply can leave in the variable list")
    void dropsNullVariables() {
        List<String> withNulls = Arrays.asList("token", null, "userId");

        JmeterGenerationResult result = new JmeterGenerationResult(JMX, "", withNulls, "");

        assertThat(result.identifiedVariables()).containsExactly("token", "userId");
    }

    @Test
    @DisplayName("defends the variable list against later mutation of the source")
    void copiesVariableList() {
        List<String> mutable = new ArrayList<>(List.of("token"));
        JmeterGenerationResult result = new JmeterGenerationResult(JMX, "", mutable, "");

        mutable.add("injected");

        assertThat(result.identifiedVariables()).containsExactly("token");
    }
}
