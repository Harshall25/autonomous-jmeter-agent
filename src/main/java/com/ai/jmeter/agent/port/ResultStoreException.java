package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when run results cannot be persisted. */
public class ResultStoreException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ResultStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
