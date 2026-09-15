package com.ai.jmeter.agent.adapter.ai;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.cost.AgentTurn;
import com.ai.jmeter.agent.domain.cost.TokenUsage;
import com.ai.jmeter.agent.domain.jmx.JmxRepairPlan;
import com.ai.jmeter.agent.domain.memory.HealPrecedent;
import com.ai.jmeter.agent.port.CostGovernorPort;
import com.ai.jmeter.agent.port.JmeterAgentException;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ResponseEntity;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Driven adapter: the agent's reasoning, backed by Spring AI's {@link ChatClient}.
 *
 * <p>Every turn binds its reply into a typed record via Spring AI's structured output. That is
 * deliberate and load-bearing: it makes Spring AI attach the record's JSON schema to the request
 * and bind the answer back, so the orchestrator receives a plan plus its test data rather than
 * prose it would have to scrape XML out of. An autonomous loop cannot afford to guess where the
 * script ends and the commentary begins.
 *
 * <p>Calls go through {@code responseEntity} rather than {@code entity} so the provider's token
 * usage comes back alongside the bound object. Without it the agent could not be given a spending
 * ceiling, and an unbounded healing loop is an unbounded bill.
 */
public final class SpringAiAgentAdapter implements JmeterAgentPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentAdapter.class);

    private final ChatClient chatClient;
    private final PromptCatalog promptCatalog;
    private final CostGovernorPort costGovernor;
    private final ModelRouter modelRouter;

    public SpringAiAgentAdapter(
            ChatClient chatClient,
            PromptCatalog promptCatalog,
            CostGovernorPort costGovernor,
            ModelRouter modelRouter) {
        this.chatClient = chatClient;
        this.promptCatalog = promptCatalog;
        this.costGovernor = costGovernor;
        this.modelRouter = modelRouter;
    }

    @Override
    public JmeterGenerationResult generateScript(String parsedTraffic, ExecutionMode mode) {
        log.debug("Requesting initial plan generation for {} mode", mode);
        return requirePlan(call(
                AgentTurn.GENERATION,
                promptCatalog.systemPromptFor(mode),
                parsedTraffic,
                JmeterGenerationResult.class), "generation");
    }

    @Override
    public JmxRepairPlan proposeRepairs(
            String structureSummary, String errorLogs, List<HealPrecedent> precedents) {
        log.debug("Requesting structured repairs with {} recalled precedent(s)", precedents.size());

        RepairPlanResponse response = call(
                AgentTurn.REPAIR,
                promptCatalog.repairSystemPrompt(structureSummary, errorLogs, precedents),
                promptCatalog.repairInstruction(),
                RepairPlanResponse.class);

        if (response == null) {
            // Not fatal: the caller falls back to regenerating the whole plan.
            log.warn("Repair turn returned nothing bindable; a full rewrite will be requested");
            return JmxRepairPlan.rewrite("Model returned no structured repair plan");
        }
        JmxRepairPlan plan = response.toDomain();
        log.info("Model proposed {} edit(s): {}", plan.mutations().size(), plan.diagnosis());
        return plan;
    }

    @Override
    public JmeterGenerationResult healScript(String currentScript, String errorLogs) {
        log.debug("Requesting full plan rewrite with {} characters of evidence", errorLogs.length());
        return requirePlan(call(
                AgentTurn.REWRITE,
                promptCatalog.healSystemPrompt(currentScript, errorLogs),
                promptCatalog.healInstruction(),
                JmeterGenerationResult.class), "self-healing");
    }

    /**
     * Single funnel for every turn, so routing, metering and error reporting cannot drift apart
     * between them.
     *
     * @throws JmeterAgentException if the model cannot be reached
     */
    private <T> T call(AgentTurn turn, String systemPrompt, String userMessage, Class<T> replyType) {
        ResponseEntity<ChatResponse, T> response;
        try {
            ChatClient.ChatClientRequestSpec request = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage);

            ChatOptions options = modelRouter.optionsFor(turn);
            if (options != null) {
                request = request.options(options);
            }
            response = request.call().responseEntity(replyType);
        } catch (RuntimeException e) {
            throw new JmeterAgentException("LLM %s turn failed".formatted(label(turn)), e);
        }

        if (response == null) {
            return null;
        }
        costGovernor.recordTurn(turn, usageOf(response.response()));
        return response.entity();
    }

    /** @return what the provider reported, or zero when it reported nothing usable. */
    private static TokenUsage usageOf(ChatResponse response) {
        if (response == null || response.getMetadata() == null) {
            return TokenUsage.UNKNOWN;
        }
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) {
            return TokenUsage.UNKNOWN;
        }
        return new TokenUsage(
                orZero(usage.getPromptTokens()), orZero(usage.getCompletionTokens()));
    }

    private static long orZero(Integer count) {
        return count == null ? 0 : count;
    }

    private static JmeterGenerationResult requirePlan(JmeterGenerationResult plan, String turn) {
        if (plan == null) {
            throw new JmeterAgentException(
                    "LLM %s turn returned no structured result".formatted(turn));
        }
        log.debug("{} turn produced a plan of {} characters", turn, plan.jmxXmlContent().length());
        return plan;
    }

    private static String label(AgentTurn turn) {
        return switch (turn) {
            case GENERATION -> "generation";
            case REPAIR -> "repair-proposal";
            case REWRITE -> "self-healing";
        };
    }
}
