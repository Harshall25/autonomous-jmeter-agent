package com.ai.jmeter.agent.domain;

import java.nio.file.Path;

/**
 * The on-disk files materialized from one {@link JmeterGenerationResult}.
 *
 * @param jmxScript the test plan JMeter will execute
 * @param csvData   the CSV Data Set Config feed referenced by the plan
 */
public record WorkspaceArtifacts(Path jmxScript, Path csvData) {
}
