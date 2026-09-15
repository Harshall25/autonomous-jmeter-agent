package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import java.util.Map;

/**
 * Driven port: materializes a generated plan onto whatever storage the execution engine reads
 * from.
 *
 * <p>Without this port the orchestrator would have to touch {@code java.nio.file} directly,
 * which would drag infrastructure concerns into the core and make the healing loop awkward to
 * test. The core asks for artifacts; an adapter decides where they land.
 */
public interface WorkspacePort {

    /**
     * Writes the plan and its CSV feed, overwriting the artifacts from any previous attempt so
     * each healing turn re-runs against the corrected files.
     *
     * @param result the plan to persist
     * @return the locations written
     * @throws WorkspaceException if the artifacts cannot be written
     */
    WorkspaceArtifacts write(JmeterGenerationResult result);

    /**
     * Persists the real values behind redaction placeholders as a JMeter properties file, so the
     * execution engine can bind credentials that were never sent to the model.
     *
     * @param bindings property name to secret value, possibly empty
     * @throws WorkspaceException if the bindings cannot be written
     */
    void writeSecretBindings(Map<String, String> bindings);

    /**
     * @return {@code true} when a JDBC driver JAR is visible on the JMeter classpath. SQL-mode
     * plans cannot connect without one, so the orchestrator warns up-front rather than letting
     * the agent burn its retry budget healing a failure it cannot fix.
     */
    boolean jdbcDriverAvailable();
}
