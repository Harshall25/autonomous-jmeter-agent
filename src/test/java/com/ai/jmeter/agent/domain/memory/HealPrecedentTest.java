package com.ai.jmeter.agent.domain.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("HealPrecedent")
class HealPrecedentTest {

    @Test
    @DisplayName("renders a successful repair as guidance")
    void describesSuccess() {
        HealPrecedent precedent = new HealPrecedent(
                "SAMPLE_FAILURE|401:login", "missed correlation",
                List.of("extract ${auth_token} from login"), true);

        assertThat(precedent.describe())
                .contains("Failure: SAMPLE_FAILURE|401:login")
                .contains("Diagnosis: missed correlation")
                .contains("Edits that fixed it");
    }

    @Test
    @DisplayName("renders a failed repair as something to avoid repeating")
    void describesFailure() {
        HealPrecedent precedent = new HealPrecedent(
                "SAMPLE_FAILURE|401:login", "wrong guess", List.of("set header X"), false);

        assertThat(precedent.describe()).contains("Edits that did NOT fix it");
    }

    @Test
    @DisplayName("normalizes a missing diagnosis and edit list")
    void normalizesNulls() {
        HealPrecedent precedent = new HealPrecedent("sig", null, null, true);

        assertThat(precedent.diagnosis()).isEmpty();
        assertThat(precedent.appliedEdits()).isEmpty();
        assertThat(precedent.describe()).isNotBlank();
    }

    @Test
    @DisplayName("defends its edit list against later mutation of the source")
    void copiesEditList() {
        List<String> mutable = new ArrayList<>(List.of("edit one"));
        HealPrecedent precedent = new HealPrecedent("sig", "d", mutable, true);

        mutable.add("injected");

        assertThat(precedent.appliedEdits()).containsExactly("edit one");
    }
}
