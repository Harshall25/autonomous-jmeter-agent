package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when a plan cannot be parsed, edited or serialized. */
public class JmxDocumentException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public JmxDocumentException(String message) {
        super(message);
    }

    public JmxDocumentException(String message, Throwable cause) {
        super(message, cause);
    }
}
