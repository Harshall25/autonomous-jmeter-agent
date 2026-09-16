package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when a run's provenance cannot be signed, recorded or read back. */
public class ProvenanceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public ProvenanceException(String message) {
        super(message);
    }

    public ProvenanceException(String message, Throwable cause) {
        super(message, cause);
    }
}
