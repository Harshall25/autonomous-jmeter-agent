package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when the gate's report cannot be published to the pipeline. */
public class BuildReportException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public BuildReportException(String message, Throwable cause) {
        super(message, cause);
    }
}
