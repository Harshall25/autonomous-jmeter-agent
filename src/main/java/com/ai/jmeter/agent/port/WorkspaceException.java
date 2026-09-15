package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when generated artifacts cannot be written to the workspace. */
public class WorkspaceException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public WorkspaceException(String message, Throwable cause) {
        super(message, cause);
    }
}
