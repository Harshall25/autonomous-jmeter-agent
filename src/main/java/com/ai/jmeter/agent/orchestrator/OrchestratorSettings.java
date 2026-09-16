package com.ai.jmeter.agent.orchestrator;

import java.util.Map;

/**
 * The policy knobs that govern how the agentic loop behaves.
 *
 * <p>Grouped rather than passed individually because the orchestrator already takes every port it
 * drives, and a constructor mixing ten collaborators with five loose ints and booleans is one
 * where callers transpose arguments silently — the compiler cannot tell {@code maxAttempts} from
 * {@code historyDepth}.
 *
 * @param maxAttempts        total JMeter runs allowed per request, counting the first
 * @param strictCompliance   refuse a capture carrying regulated material rather than substituting
 * @param recalledPrecedents how many past repairs to put in front of the model per turn
 * @param historyDepth       how many past runs a regression baseline is built from
 * @param traceCorrelation   emit a W3C traceparent per request so results join to server spans
 * @param promptRevision     which revision of the agent's prompts is in force, recorded in the
 *                           run's provenance so a plan can be traced back to the instructions
 *                           that produced it
 * @param modelsByTurn       which model answers each kind of turn, recorded for the same reason
 */
public record OrchestratorSettings(
        int maxAttempts,
        boolean strictCompliance,
        int recalledPrecedents,
        int historyDepth,
        boolean traceCorrelation,
        String promptRevision,
        Map<String, String> modelsByTurn) {

    public OrchestratorSettings {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
        }
        promptRevision = promptRevision == null ? "" : promptRevision;
        modelsByTurn = modelsByTurn == null ? Map.of() : Map.copyOf(modelsByTurn);
    }

    /** The loop's policy, without the provenance labels a hosted deployment supplies. */
    public OrchestratorSettings(
            int maxAttempts,
            boolean strictCompliance,
            int recalledPrecedents,
            int historyDepth,
            boolean traceCorrelation) {
        this(maxAttempts, strictCompliance, recalledPrecedents, historyDepth, traceCorrelation,
                "", Map.of());
    }

    /** Sensible defaults for a local run: three attempts, no compliance gate, no tracing. */
    public static OrchestratorSettings defaults() {
        return new OrchestratorSettings(3, false, 3, 10, false);
    }
}
