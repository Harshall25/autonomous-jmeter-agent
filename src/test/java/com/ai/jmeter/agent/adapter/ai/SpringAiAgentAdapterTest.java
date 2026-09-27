package com.ai.jmeter.agent.adapter.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.port.JmeterAgentException;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;

/**
 * Exercises the reasoning adapter against a mocked {@link ChatClient}, so the full fluent chain
 * is verified without a single call reaching Anthropic.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("SpringAiAgentAdapter")
class SpringAiAgentAdapterTest {

    private static final JmeterGenerationResult EXPECTED = new JmeterGenerationResult(
            "<jmeterTestPlan/>", "user,pass\nalice,s3cret", List.of("user", "pass"), "Looks good");

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private SpringAiAgentAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new SpringAiAgentAdapter(chatClient, new PromptCatalog(
                new ClassPathResource("prompts/api-jmeter-system.st"),
                new ClassPathResource("prompts/sql-jmeter-system.st"),
                new ClassPathResource("prompts/heal-script.st")));
    }

    /** Wires the fluent chain so {@code prompt().system().user().call().entity()} resolves. */
    private void stubChain() {
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
    }

    @Test
    @DisplayName("binds the reply into a typed record rather than scraping prose")
    void generateBindsStructuredOutput() {
        stubChain();
        when(responseSpec.entity(JmeterGenerationResult.class)).thenReturn(EXPECTED);

        JmeterGenerationResult result = adapter.generateScript("[traffic]", ExecutionMode.API);

        assertThat(result).isSameAs(EXPECTED);
        verify(responseSpec).entity(JmeterGenerationResult.class);
    }

    @Test
    @DisplayName("briefs the model with the API prompt and the parsed traffic")
    void generateUsesApiPrompt() {
        stubChain();
        when(responseSpec.entity(JmeterGenerationResult.class)).thenReturn(EXPECTED);

        adapter.generateScript("[{\"url\":\"/v1/login\"}]", ExecutionMode.API);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue()).contains("Auto-Correlate").doesNotContain("JDBC");
        verify(requestSpec).user("[{\"url\":\"/v1/login\"}]");
    }

    @Test
    @DisplayName("briefs the model with the SQL prompt in SQL mode")
    void generateUsesSqlPrompt() {
        stubChain();
        when(responseSpec.entity(JmeterGenerationResult.class)).thenReturn(EXPECTED);

        adapter.generateScript("SELECT 1;", ExecutionMode.SQL);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue()).contains("JDBC Request Sampler");
    }

    @Test
    @DisplayName("hands the failed plan and the error evidence to the repair turn")
    void healSendsScriptAndErrors() {
        stubChain();
        when(responseSpec.entity(JmeterGenerationResult.class)).thenReturn(EXPECTED);

        adapter.healScript("<broken/>", "401 Unauthorized on /v1/orders");

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue())
                .contains("<broken/>")
                .contains("401 Unauthorized on /v1/orders");
        verify(requestSpec).user("Return the corrected JMeter test plan and its matching CSV data set.");
    }

    @Test
    @DisplayName("fails loudly when the model returns nothing bindable")
    void rejectsNullEntity() {
        stubChain();
        when(responseSpec.entity(JmeterGenerationResult.class)).thenReturn(null);

        assertThatThrownBy(() -> adapter.generateScript("[]", ExecutionMode.API))
                .isInstanceOf(JmeterAgentException.class)
                .hasMessageContaining("generation turn returned no structured result");
    }

    @Test
    @DisplayName("fails loudly when the repair turn returns nothing bindable")
    void rejectsNullEntityWhileHealing() {
        stubChain();
        when(responseSpec.entity(JmeterGenerationResult.class)).thenReturn(null);

        assertThatThrownBy(() -> adapter.healScript("<broken/>", "500"))
                .isInstanceOf(JmeterAgentException.class)
                .hasMessageContaining("self-healing turn returned no structured result");
    }

    @Test
    @DisplayName("wraps a transport failure so the orchestrator sees one exception type")
    void wrapsModelFailure() {
        when(chatClient.prompt()).thenThrow(new IllegalStateException("connection reset"));

        assertThatThrownBy(() -> adapter.generateScript("[]", ExecutionMode.API))
                .isInstanceOf(JmeterAgentException.class)
                .hasMessageContaining("LLM generation turn failed")
                .hasRootCauseMessage("connection reset");
    }
}
