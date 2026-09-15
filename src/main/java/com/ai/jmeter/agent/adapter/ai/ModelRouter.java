package com.ai.jmeter.agent.adapter.ai;

import com.ai.jmeter.agent.domain.cost.AgentTurn;
import java.util.Map;
import org.springframework.ai.chat.prompt.ChatOptions;

/**
 * Chooses which model serves which kind of turn.
 *
 * <p>The turns are not equally hard. Diagnosing why a plan returned 401 and choosing the right
 * correlation is the reasoning the agent lives or dies on, and is worth a frontier model. Emitting
 * a plan from an already-minimized traffic summary is comparatively mechanical, and on a large
 * estate it is also the bulk of the token spend. Routing them separately is the difference between
 * paying frontier prices for everything and paying them only where they buy accuracy.
 *
 * <p>An unconfigured turn falls through to the client's default model, so the router is inert
 * until an operator actually opts into routing.
 */
public final class ModelRouter {

    private final Map<AgentTurn, String> modelsByTurn;

    public ModelRouter(Map<AgentTurn, String> modelsByTurn) {
        this.modelsByTurn = Map.copyOf(modelsByTurn);
    }

    /** A router that leaves every turn on the client's configured default. */
    public static ModelRouter usingDefaults() {
        return new ModelRouter(Map.of());
    }

    /**
     * @param turn the turn about to be made
     * @return options pinning the model for that turn, or {@code null} to use the client default
     */
    public ChatOptions optionsFor(AgentTurn turn) {
        String model = modelsByTurn.get(turn);
        return model == null ? null : ChatOptions.builder().model(model).build();
    }
}
