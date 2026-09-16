package com.ai.jmeter.agent.adapter.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.adapter.ai.SpringAiRootCauseAnalyzer.RootCauseResponse;
import com.ai.jmeter.agent.domain.analysis.RegressionVerdict;
import com.ai.jmeter.agent.domain.analysis.RootCauseHypothesis;
import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import com.ai.jmeter.agent.domain.cost.AgentTurn;
import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import com.ai.jmeter.agent.support.TestFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("SpringAiRootCauseAnalyzer")
class SpringAiRootCauseAnalyzerTest {

    @Mock
    private ChatClient chatClient;

    @Mock
    private ChatClient.ChatClientRequestSpec requestSpec;

    @Mock
    private ChatClient.CallResponseSpec responseSpec;

    private BudgetedCostGovernor costGovernor;
    private SpringAiRootCauseAnalyzer analyzer;

    @BeforeEach
    void setUp() {
        costGovernor = new BudgetedCostGovernor(0);
        costGovernor.beginRun();
        analyzer = new SpringAiRootCauseAnalyzer(
                chatClient, TestFixtures.promptCatalog(), costGovernor);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(responseSpec);
    }

    private static RunAnalysis analysis() {
        RunSummary summary = new RunSummary("run-1", "plan-a", Instant.EPOCH,
                Map.of("checkout", new SampleStatistics("checkout", 100, 0, 90, 80, 900, 1200, 2000)),
                10, 40);
        return new RunAnalysis(summary, List.of(
                new RegressionVerdict("checkout", 100, 900, 9.0, true, "outside normal variance")));
    }

    private static <T> ResponseEntity<ChatResponse, T> reply(T entity, int prompt, int completion) {
        return new ResponseEntity<>(ChatResponse.builder()
                .generations(List.of(new Generation(new AssistantMessage("{}"))))
                .metadata(ChatResponseMetadata.builder()
                        .usage(new DefaultUsage(prompt, completion))
                        .build())
                .build(), entity);
    }

    @Test
    @DisplayName("returns the model's hypotheses with their evidence attached")
    void returnsHypotheses() {
        when(responseSpec.responseEntity(RootCauseResponse.class)).thenReturn(reply(
                new RootCauseResponse(List.of(new RootCauseResponse.Hypothesis(
                        "Connection pool exhaustion on checkout",
                        "p50 held at 80ms while p99 reached 1200ms",
                        "Check pool saturation metrics during the run window",
                        75))),
                100, 200));

        List<RootCauseHypothesis> hypotheses = analyzer.explain(analysis());

        assertThat(hypotheses).singleElement().satisfies(hypothesis -> {
            assertThat(hypothesis.summary()).contains("Connection pool");
            assertThat(hypothesis.evidence()).contains("p99");
            assertThat(hypothesis.suggestedAction()).contains("pool saturation");
            assertThat(hypothesis.confidence()).isEqualTo(75);
        });
    }

    @Test
    @DisplayName("briefs the model with what the run measured and how it compared")
    void briefsWithTheAnalysis() {
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(reply(new RootCauseResponse(List.of()), 10, 10));

        analyzer.explain(analysis());

        ArgumentCaptor<String> system = ArgumentCaptor.forClass(String.class);
        verify(requestSpec).system(system.capture());
        assertThat(system.getValue())
                .contains("checkout")
                .contains("REGRESSED")
                .contains("hypothesis with its evidence");
    }

    @Test
    @DisplayName("books the diagnosis against the run's token budget")
    void metersTheDiagnosis() {
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(reply(new RootCauseResponse(List.of()), 100, 250));

        analyzer.explain(analysis());

        assertThat(costGovernor.currentCost().byTurn())
                .containsKey(AgentTurn.REPAIR);
        assertThat(costGovernor.currentCost().totalTokens()).isEqualTo(350);
    }

    @Test
    @DisplayName("accepts that a healthy run has nothing to explain")
    void emptyHypothesesAreValid() {
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(reply(new RootCauseResponse(List.of()), 10, 10));

        assertThat(analyzer.explain(analysis())).isEmpty();
    }

    @Test
    @DisplayName("tolerates a reply with no hypothesis list at all")
    void tolerateNullHypotheses() {
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(reply(new RootCauseResponse(null), 10, 10));

        assertThat(analyzer.explain(analysis())).isEmpty();
    }

    @Test
    @DisplayName("defaults a hypothesis the model gave no confidence for")
    void defaultsMissingConfidence() {
        when(responseSpec.responseEntity(RootCauseResponse.class)).thenReturn(reply(
                new RootCauseResponse(List.of(new RootCauseResponse.Hypothesis(
                        "A guess", "some evidence", "check it", null))),
                10, 10));

        assertThat(analyzer.explain(analysis())).singleElement()
                .satisfies(hypothesis -> assertThat(hypothesis.confidence()).isZero());
    }

    @Test
    @DisplayName("loses the commentary rather than the result when the model is unreachable")
    void modelFailureIsNotFatal() {
        // Diagnosis is commentary on a run that already passed and was already recorded.
        when(chatClient.prompt()).thenThrow(new IllegalStateException("429 rate limited"));

        assertThat(analyzer.explain(analysis())).isEmpty();
    }

    @Test
    @DisplayName("treats an unbindable reply as no diagnosis")
    void unbindableReplyYieldsNothing() {
        when(responseSpec.responseEntity(RootCauseResponse.class)).thenReturn(null);

        assertThat(analyzer.explain(analysis())).isEmpty();
    }

    @Test
    @DisplayName("treats a reply with no entity as no diagnosis")
    void nullEntityYieldsNothing() {
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(new ResponseEntity<>(null, null));

        assertThat(analyzer.explain(analysis())).isEmpty();
    }

    @Test
    @DisplayName("keeps accounting when the provider reports no usage")
    void toleratesMissingUsage() {
        when(responseSpec.responseEntity(RootCauseResponse.class)).thenReturn(
                new ResponseEntity<>(
                        new ChatResponse(List.of(new Generation(new AssistantMessage("{}")))),
                        new RootCauseResponse(List.of())));

        analyzer.explain(analysis());

        assertThat(costGovernor.currentCost().totalTokens()).isZero();
    }

    @Test
    @DisplayName("ranks hypotheses by confidence when reporting them")
    void ranksByConfidence() {
        String report = RootCauseHypothesis.report(List.of(
                new RootCauseHypothesis("unlikely", "weak", "check", 20),
                new RootCauseHypothesis("likely", "strong", "check", 90)));

        assertThat(report.indexOf("likely")).isLessThan(report.indexOf("unlikely"));
        assertThat(report).contains("[90% confidence]");
    }

    @Test
    @DisplayName("says plainly when nothing was diagnosed")
    void reportsNoHypotheses() {
        assertThat(RootCauseHypothesis.report(List.of()))
                .isEqualTo("No root-cause hypotheses were produced.");
    }

    @Test
    @DisplayName("normalizes a hypothesis the model left half-filled")
    void normalizesPartialHypothesis() {
        RootCauseHypothesis hypothesis = new RootCauseHypothesis(null, null, null, 500);

        assertThat(hypothesis.summary()).isEmpty();
        assertThat(hypothesis.evidence()).isEmpty();
        assertThat(hypothesis.suggestedAction()).isEmpty();
        assertThat(hypothesis.confidence()).isEqualTo(100);
        assertThat(new RootCauseHypothesis("s", "e", "a", -10).confidence()).isZero();
    }

    @Test
    @DisplayName("keeps accounting when the reply carries no response envelope")
    void toleratesMissingChatResponse() {
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(new ResponseEntity<>(null, new RootCauseResponse(List.of())));

        analyzer.explain(analysis());

        assertThat(costGovernor.currentCost().totalTokens()).isZero();
    }

    @Test
    @DisplayName("keeps accounting when the response carries no metadata")
    void toleratesMissingMetadata() {
        ChatResponse response = org.mockito.Mockito.mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(null);
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(new ResponseEntity<>(response, new RootCauseResponse(List.of())));

        analyzer.explain(analysis());

        assertThat(costGovernor.currentCost().totalTokens()).isZero();
    }

    @Test
    @DisplayName("keeps accounting when the metadata carries no usage")
    void toleratesMissingUsageObject() {
        ChatResponseMetadata metadata = org.mockito.Mockito.mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(null);
        ChatResponse response = org.mockito.Mockito.mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(new ResponseEntity<>(response, new RootCauseResponse(List.of())));

        analyzer.explain(analysis());

        assertThat(costGovernor.currentCost().totalTokens()).isZero();
    }

    private void replyReportingUsage(Integer promptTokens, Integer completionTokens) {
        Usage usage = org.mockito.Mockito.mock(Usage.class);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        ChatResponseMetadata metadata = org.mockito.Mockito.mock(ChatResponseMetadata.class);
        when(metadata.getUsage()).thenReturn(usage);
        ChatResponse response = org.mockito.Mockito.mock(ChatResponse.class);
        when(response.getMetadata()).thenReturn(metadata);
        when(responseSpec.responseEntity(RootCauseResponse.class))
                .thenReturn(new ResponseEntity<>(response, new RootCauseResponse(List.of())));
    }

    @Test
    @DisplayName("counts the prompt side when the provider omits completion tokens")
    void countsPartialUsage() {
        replyReportingUsage(80, null);

        analyzer.explain(analysis());

        assertThat(costGovernor.currentCost().totalTokens()).isEqualTo(80);
    }

    @Test
    @DisplayName("counts the completion side when the provider omits prompt tokens")
    void countsCompletionOnlyUsage() {
        replyReportingUsage(null, 45);

        analyzer.explain(analysis());

        assertThat(costGovernor.currentCost().totalTokens()).isEqualTo(45);
    }
}
