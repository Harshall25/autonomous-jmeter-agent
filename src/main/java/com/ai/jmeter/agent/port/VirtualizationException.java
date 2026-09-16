package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when dependency stubs cannot be materialized. */
public class VirtualizationException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public VirtualizationException(String message, Throwable cause) {
        super(message, cause);
    }
}
