package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when a JMeter run cannot be attempted or its results cannot be read. */
public class ExecutionEngineException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ExecutionEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}
