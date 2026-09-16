package com.ai.jmeter.agent.domain.governance;

import java.io.Serial;

/**
 * Raised when a principal asks for something their roles do not allow.
 *
 * <p>The message names the permission that was missing but never the resource, because a denial
 * that distinguishes "you may not see this run" from "there is no such run" tells an outsider
 * which run ids exist.
 */
public class AccessDeniedException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public AccessDeniedException(Permission required) {
        super("This principal lacks the " + required + " permission");
    }
}
