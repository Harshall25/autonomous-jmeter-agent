package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when a capture cannot be read, is malformed, or contains no usable traffic. */
public class TrafficParsingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public TrafficParsingException(String message) {
        super(message);
    }

    public TrafficParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
