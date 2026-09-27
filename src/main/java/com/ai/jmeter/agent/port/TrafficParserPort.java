package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.ExecutionMode;
import java.nio.file.Path;

/**
 * Driven port: turns a raw capture on disk into a token-efficient summary the LLM can reason
 * about.
 *
 * <p>Minimization is the whole point of this port. A real HAR file is overwhelmingly static
 * assets and a slow query log is overwhelmingly repetition; feeding either verbatim to a model
 * wastes context on noise and degrades the generated plan.
 */
public interface TrafficParserPort {

    /**
     * @return the ingestion mode this parser handles, used to route a run to the right
     * implementation
     */
    ExecutionMode supportedMode();

    /**
     * Reads and minimizes the capture.
     *
     * @param sourceFile the {@code .har} capture or SQL log to ingest
     * @return a compact, model-ready summary of the meaningful traffic
     * @throws TrafficParsingException if the file cannot be read or holds no usable traffic
     */
    String parse(Path sourceFile);
}
