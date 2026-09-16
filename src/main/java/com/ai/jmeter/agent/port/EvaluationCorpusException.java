package com.ai.jmeter.agent.port;

import java.io.Serial;

/** Raised when the golden corpus cannot be read or does not declare a runnable suite. */
public class EvaluationCorpusException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EvaluationCorpusException(String message) {
        super(message);
    }

    public EvaluationCorpusException(String message, Throwable cause) {
        super(message, cause);
    }
}
