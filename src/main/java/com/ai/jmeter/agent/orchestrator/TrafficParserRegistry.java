package com.ai.jmeter.agent.orchestrator;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.port.TrafficParserPort;
import java.util.Collection;
import java.util.EnumMap;
import java.util.Map;

/**
 * Routes a run to the parser that handles its {@link ExecutionMode}.
 *
 * <p>Keeps the orchestrator free of {@code if (sqlMode)} branching: adding a third ingestion
 * mode later means adding a parser adapter, not editing the loop.
 */
public final class TrafficParserRegistry {

    private final Map<ExecutionMode, TrafficParserPort> parsersByMode;

    /**
     * @param parsers every available parser adapter
     * @throws IllegalArgumentException if two parsers claim the same mode, which would make
     *                                  routing non-deterministic
     */
    public TrafficParserRegistry(Collection<TrafficParserPort> parsers) {
        Map<ExecutionMode, TrafficParserPort> registry = new EnumMap<>(ExecutionMode.class);
        for (TrafficParserPort parser : parsers) {
            TrafficParserPort previous = registry.put(parser.supportedMode(), parser);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "Duplicate parser registered for mode " + parser.supportedMode());
            }
        }
        this.parsersByMode = Map.copyOf(registry);
    }

    /**
     * @param mode the mode to resolve
     * @return the parser registered for {@code mode}
     * @throws IllegalStateException if no parser handles the mode
     */
    public TrafficParserPort parserFor(ExecutionMode mode) {
        TrafficParserPort parser = parsersByMode.get(mode);
        if (parser == null) {
            throw new IllegalStateException("No traffic parser registered for mode " + mode);
        }
        return parser;
    }
}
