package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.redaction.RedactionResult;

/**
 * Driven port: strips sensitive material from a capture before it crosses the process boundary.
 *
 * <p>This port sits between parsing and reasoning, and it is the only reason a production capture
 * can be handed to a third-party model at all. A HAR recording is a bag of live bearer tokens,
 * session cookies and customer data; a slow query log carries whatever was bound into the
 * {@code WHERE} clause. Both are regulated data the moment they leave the host.
 */
public interface SensitiveDataRedactorPort {

    /**
     * Substitutes every detected secret with a stable placeholder.
     *
     * @param payload the parsed capture summary
     * @return the safe payload plus the bindings needed to restore real values at execution time
     */
    RedactionResult redact(String payload);
}
