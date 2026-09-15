package com.ai.jmeter.agent.adapter.ai;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.port.JmeterAgentException;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * Driven adapter: the agent's reasoning, backed by Spring AI's {@link ChatClient}.
 *
 * <p>Both operations end in {@code .entity(JmeterGenerationResult.class)}. That is deliberate and
 * load-bearing: it makes Spring AI attach the record's JSON schema to the request and bind the
 * reply back into a typed record, so the orchestrator receives a plan plus its test data rather
 * than prose it would have to scrape XML out of. An autonomous loop cannot afford to guess where
 * the script ends and the commentary begins.
 */
public final class SpringAiAgentAdapter implements JmeterAgentPort {

    private static final Logger log = LoggerFactory.getLogger(SpringAiAgentAdapter.class);

    private final ChatClient chatClient;
    private final PromptCatalog promptCatalog;

    public SpringAiAgentAdapter(ChatClient chatClient, PromptCatalog promptCatalog) {
        this.chatClient = chatClient;
        this.promptCatalog = promptCatalog;
    }

    @Override
    public JmeterGenerationResult generateScript(String parsedTraffic, ExecutionMode mode) {
        log.debug("Requesting initial plan generation for {} mode", mode);
        return callModel(promptCatalog.systemPromptFor(mode), parsedTraffic, "generation");
    }

    @Override
    public JmeterGenerationResult healScript(String currentScript, String errorLogs) {
        log.debug("Requesting plan repair with {} characters of error evidence", errorLogs.length());
        return callModel(
                promptCatalog.healSystemPrompt(currentScript, errorLogs),
                promptCatalog.healInstruction(),
                "self-healing");
    }

    /**
     * Single funnel for both turns, so generation and healing cannot drift apart in how they
     * bind output or report failure.
     *
     * @param systemPrompt the instructions briefing the model
     * @param userMessage  the payload the model reasons over
     * @param turn         label used in diagnostics
     * @return the typed plan
     * @throws JmeterAgentException if the model call fails or yields no usable plan
     */
    private JmeterGenerationResult callModel(String systemPrompt, String userMessage, String turn) {
        JmeterGenerationResult result;
        try {
            result = chatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .entity(JmeterGenerationResult.class);
        } catch (RuntimeException e) {
            throw new JmeterAgentException("LLM %s turn failed".formatted(turn), e);
        }

        if (result == null) {
            throw new JmeterAgentException(
                    "LLM %s turn returned no structured result".formatted(turn));
        }
        log.debug("{} turn produced a plan of {} characters", turn, result.jmxXmlContent().length());
        return result;
    }
}
