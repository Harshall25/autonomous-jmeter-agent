package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when the reasoning port cannot produce a usable plan. */
public class JmeterAgentException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JmeterAgentException(String message) {
        super(message);
    }

    public JmeterAgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
