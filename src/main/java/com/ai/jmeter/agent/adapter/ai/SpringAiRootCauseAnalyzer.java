package com.ai.jmeter.agent.adapter.ai;

import com.ai.jmeter.agent.domain.analysis.RootCauseHypothesis;
import com.ai.jmeter.agent.domain.analysis.RunAnalysis;
import com.ai.jmeter.agent.domain.cost.AgentTurn;
import com.ai.jmeter.agent.domain.cost.TokenUsage;
import com.ai.jmeter.agent.port.CostGovernorPort;
import com.ai.jmeter.agent.port.RootCauseAnalyzerPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;

/**
 * Driven adapter: asks the model to explain a slow run.
 *
 * <p>Failures here are swallowed rather than propagated. Diagnosis is commentary on a run that has
 * already passed and already been recorded; losing the commentary because the model was rate
 * limited is a far better outcome than losing the result.
 */
public final class SpringAiRootCauseAnalyzer implements RootCauseAnalyzerPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiRootCauseAnalyzer.class);

    private static final String INSTRUCTION =
            "Return ranked root-cause hypotheses with the evidence behind each.";

    private final ChatClient chatClient;
    private final PromptCatalog promptCatalog;
    private final CostGovernorPort costGovernor;

    public SpringAiRootCauseAnalyzer(
            ChatClient chatClient, PromptCatalog promptCatalog, CostGovernorPort costGovernor) {
        this.chatClient = chatClient;
        this.promptCatalog = promptCatalog;
        this.costGovernor = costGovernor;
    }

    @Override
    public List<RootCauseHypothesis> explain(RunAnalysis analysis) {
        try {
            ResponseEntity<ChatResponse, RootCauseResponse> response = chatClient.prompt()
                    .system(promptCatalog.rootCauseSystemPrompt(analysis.describe()))
                    .user(INSTRUCTION)
                    .call()
                    .responseEntity(RootCauseResponse.class);

            if (response == null || response.entity() == null) {
                log.warn("Root-cause turn returned nothing bindable");
                return List.of();
            }
            costGovernor.recordTurn(AgentTurn.REPAIR, usageOf(response.response()));

            List<RootCauseHypothesis> hypotheses = response.entity().toDomain();
            log.info("Root-cause analysis produced {} hypothesis(es)", hypotheses.size());
            return hypotheses;
        } catch (RuntimeException e) {
            // Commentary on a run that already passed and was already recorded. Losing the
            // commentary beats losing the result.
            log.warn("Root-cause analysis failed ({}); the run result stands", e.getMessage());
            return List.of();
        }
    }

    private static TokenUsage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return TokenUsage.UNKNOWN;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return TokenUsage.UNKNOWN;
        }
        return new TokenUsage(
                usage.getPromptTokens() == null ? 0 : usage.getPromptTokens(),
                usage.getCompletionTokens() == null ? 0 : usage.getCompletionTokens());
    }

    /** The wire shape of the model's reply, narrowed into the domain type. */
    record RootCauseResponse(List<Hypothesis> hypotheses) {

        record Hypothesis(
                String summary, String evidence, String suggestedAction, Integer confidence) {
        }

        List<RootCauseHypothesis> toDomain() {
            if (hypotheses == null) {
                return List.of();
            }
            return hypotheses.stream()
                    .map(entry -> new RootCauseHypothesis(
                            entry.summary(),
                            entry.evidence(),
                            entry.suggestedAction(),
                            entry.confidence() == null ? 0 : entry.confidence()))
                    .toList();
        }
    }
}
