package com.ai.jmeter.agent.adapter.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.cost.AgentTurn;
import com.ai.jmeter.agent.domain.cost.TokenUsage;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.jmx.JmxRepairPlan;
import com.ai.jmeter.agent.port.JmeterAgentException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import com.ai.jmeter.agent.support.TestFixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;

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
    private BudgetedCostGovernor costGovernor;

    @BeforeEach
    void setUp() {
        costGovernor = new BudgetedCostGovernor(0);
        adapter = new SpringAiAgentAdapter(
                chatClient, TestFixtures.promptCatalog(), costGovernor,
                ModelRouter.usingDefaults());
    }

    /** Wraps a bound reply in the envelope the adapter reads token usage from. */
    private static <T> ResponseEntity<ChatResponse, T> chatEntity(T entity) {
        return new ResponseEntity<>(
                new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))), entity);
    }

    /** Wires the fluent chain so {@code prompt().system().user().call()} resolves. */
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
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(chatEntity(EXPECTED));

        JmeterGenerationResult result = adapter.generateScript("[traffic]", ExecutionMode.API);

        assertThat(result).isSameAs(EXPECTED);
        verify(responseSpec).responseEntity(JmeterGenerationResult.class);
    }

    @Test
    @DisplayName("briefs the model with the API prompt and the parsed traffic")
    void generateUsesApiPrompt() {
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(chatEntity(EXPECTED));

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
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(chatEntity(EXPECTED));

        adapter.generateScript("SELECT 1;", ExecutionMode.SQL);

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue()).contains("JDBC Request Sampler");
    }

    @Test
    @DisplayName("hands the failed plan and the error evidence to the repair turn")
    void healSendsScriptAndErrors() {
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(chatEntity(EXPECTED));

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
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(chatEntity(null));

        assertThatThrownBy(() -> adapter.generateScript("[]", ExecutionMode.API))
                .isInstanceOf(JmeterAgentException.class)
                .hasMessageContaining("generation turn returned no structured result");
    }

    @Test
    @DisplayName("fails loudly when the repair turn returns nothing bindable")
    void rejectsNullEntityWhileHealing() {
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(chatEntity(null));

        assertThatThrownBy(() -> adapter.healScript("<broken/>", "500"))
                .isInstanceOf(JmeterAgentException.class)
                .hasMessageContaining("self-healing turn returned no structured result");
    }

    @Test
    @DisplayName("asks for structural edits and narrows them into the domain type")
    void proposeRepairsBindsMutations() {
        stubChain();
        when(responseSpec.responseEntity(RepairPlanResponse.class)).thenReturn(chatEntity(new RepairPlanResponse(
                "Login response was never mined", false,
                List.of(new RepairPlanResponse.MutationCommand(
                        "jsonPathExtractor", "login", "auth_token", "$.token",
                        null, null, null, null, null, null, null, null,
                        null, null, null, null, null)))));

        JmxRepairPlan plan = adapter.proposeRepairs("Samplers: [login]", "401 Unauthorized", List.of());

        assertThat(plan.requiresFullRewrite()).isFalse();
        assertThat(plan.mutations()).hasSize(1);
        assertThat(plan.diagnosis()).isEqualTo("Login response was never mined");
    }

    @Test
    @DisplayName("briefs the repair turn with the plan structure and the run evidence")
    void proposeRepairsSendsStructureAndEvidence() {
        stubChain();
        when(responseSpec.responseEntity(RepairPlanResponse.class)).thenReturn(
                chatEntity(new RepairPlanResponse("d", false, List.of())));

        adapter.proposeRepairs("Samplers: [login]", "401 Unauthorized", List.of());

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue())
                .contains("Samplers: [login]")
                .contains("401 Unauthorized")
                .contains("Do NOT rewrite the plan");
        verify(requestSpec).user("Return the smallest set of structural edits that fixes this failure.");
    }

    @Test
    @DisplayName("asks for a rewrite when the repair turn returns nothing bindable")
    void proposeRepairsFallsBackWhenUnbindable() {
        stubChain();
        when(responseSpec.responseEntity(RepairPlanResponse.class)).thenReturn(chatEntity(null));

        JmxRepairPlan plan = adapter.proposeRepairs("Samplers: [login]", "401", List.of());

        assertThat(plan.requiresFullRewrite())
                .as("an unusable reply must not stall the loop, it falls back to regeneration")
                .isTrue();
    }

    @Test
    @DisplayName("wraps a transport failure during the repair turn")
    void wrapsRepairTurnFailure() {
        when(chatClient.prompt()).thenThrow(new IllegalStateException("429 rate limited"));

        assertThatThrownBy(() -> adapter.proposeRepairs("structure", "errors", List.of()))
                .isInstanceOf(JmeterAgentException.class)
                .hasMessageContaining("LLM repair-proposal turn failed");
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

    @Test
    @DisplayName("meters what each turn consumed against the run's budget")
    void metersTokenUsage() {
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(new ResponseEntity<>(responseWithUsage(120, 480), EXPECTED));
        costGovernor.beginRun();

        adapter.generateScript("[traffic]", ExecutionMode.API);

        assertThat(costGovernor.currentCost().byTurn())
                .containsEntry(AgentTurn.GENERATION, new TokenUsage(120, 480));
    }

    @Test
    @DisplayName("books a repair turn's spend separately from generation")
    void metersRepairTurnSeparately() {
        stubChain();
        when(responseSpec.responseEntity(RepairPlanResponse.class))
                .thenReturn(new ResponseEntity<>(responseWithUsage(30, 70),
                        new RepairPlanResponse("d", false, List.of())));
        costGovernor.beginRun();

        adapter.proposeRepairs("structure", "errors", List.of());

        assertThat(costGovernor.currentCost().byTurn())
                .containsEntry(AgentTurn.REPAIR, new TokenUsage(30, 70));
    }

    @Test
    @DisplayName("keeps accounting when the provider reports no usage at all")
    void toleratesMissingUsage() {
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(chatEntity(EXPECTED));
        costGovernor.beginRun();

        adapter.generateScript("[traffic]", ExecutionMode.API);

        assertThat(costGovernor.currentCost().totalTokens()).isZero();
    }

    @Test
    @DisplayName("treats a null envelope as an unusable reply rather than a crash")
    void toleratesNullResponseEnvelope() {
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class)).thenReturn(null);

        assertThatThrownBy(() -> adapter.generateScript("[]", ExecutionMode.API))
                .isInstanceOf(JmeterAgentException.class)
                .hasMessageContaining("generation turn returned no structured result");
    }

    @Test
    @DisplayName("pins the model when routing is configured for that turn")
    void appliesModelRouting() {
        SpringAiAgentAdapter routed = new SpringAiAgentAdapter(
                chatClient, TestFixtures.promptCatalog(), costGovernor,
                new ModelRouter(Map.of(AgentTurn.GENERATION, "cheap-model")));
        stubChain();
        when(requestSpec.options(any(ChatOptions.class))).thenReturn(requestSpec);
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(chatEntity(EXPECTED));

        routed.generateScript("[traffic]", ExecutionMode.API);

        ArgumentCaptor<ChatOptions> options = ArgumentCaptor.forClass(ChatOptions.class);
        verify(requestSpec).options(options.capture());
        assertThat(options.getValue().getModel()).isEqualTo("cheap-model");
    }

    @Test
    @DisplayName("labels each turn distinctly when reporting a transport failure")
    void labelsEachTurnOnFailure() {
        when(chatClient.prompt()).thenThrow(new IllegalStateException("down"));

        assertThatThrownBy(() -> adapter.healScript("<broken/>", "500"))
                .hasMessageContaining("LLM self-healing turn failed");
    }

    /** A response carrying provider-reported usage. */
    private static ChatResponse responseWithUsage(int promptTokens, int completionTokens) {
        return ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("{}"))))
                .metadata(ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(promptTokens, completionTokens))
                        .build())
                .build();
    }

    @Test
    @DisplayName("keeps accounting when the reply carries no response envelope")
    void toleratesMissingChatResponse() {
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(new ResponseEntity<>(null, EXPECTED));
        costGovernor.beginRun();

        adapter.generateScript("[traffic]", ExecutionMode.API);

        assertThat(costGovernor.currentCost().totalTokens()).isZero();
    }

    @Test
    @DisplayName("keeps accounting when the response carries no metadata")
    void toleratesMissingMetadata() {
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(null);
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(new ResponseEntity<>(response, EXPECTED));
        costGovernor.beginRun();

        adapter.generateScript("[traffic]", ExecutionMode.API);

        assertThat(costGovernor.currentCost().totalTokens()).isZero();
    }

    @Test
    @DisplayName("keeps accounting when the metadata carries no usage")
    void toleratesMissingUsageObject() {
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(null);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(new ResponseEntity<>(response, EXPECTED));
        costGovernor.beginRun();

        adapter.generateScript("[traffic]", ExecutionMode.API);

        assertThat(costGovernor.currentCost().totalTokens()).isZero();
    }

    @Test
    @DisplayName("counts a partially reported usage rather than discarding it")
    void countsPartialUsage() {
        Usage usage = mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(90);
        when(usage.getCompletionTokens()).thenReturn(null);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse response = mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);
        stubChain();
        when(responseSpec.responseEntity(JmeterGenerationResult.class))
                .thenReturn(new ResponseEntity<>(response, EXPECTED));
        costGovernor.beginRun();

        adapter.generateScript("[traffic]", ExecutionMode.API);

        assertThat(costGovernor.currentCost().byTurn())
                .containsEntry(AgentTurn.GENERATION, new TokenUsage(90, 0));
    }
}
