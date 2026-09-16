package com.ai.jmeter.agent.domain;

import java.nio.file.Path;
import java.util.Map;

/**
 * The on-disk files materialized from one {@link JmeterGenerationResult}.
 *
 * <p>The digests are recorded by whatever wrote the bytes, so that a provenance record can be
 * matched back to the artifact months later. Taken at write time rather than read time on
 * purpose: a digest computed when the audit is performed attests only that the file has not
 * changed since the audit began.
 *
 * @param jmxScript  the test plan JMeter will execute
 * @param csvData    the CSV Data Set Config feed referenced by the plan
 * @param jmxDigest  digest of the plan as written, or empty when it was not recorded
 * @param csvDigest  digest of the test data as written, or empty when it was not recorded
 */
public record WorkspaceArtifacts(
        Path jmxScript,
        Path csvData,
        String jmxDigest,
        String csvDigest) {

    public WorkspaceArtifacts {
        jmxDigest = jmxDigest == null ? "" : jmxDigest;
        csvDigest = csvDigest == null ? "" : csvDigest;
    }

    /** Artifacts whose digests were not recorded, for a caller that only needs the paths. */
    public WorkspaceArtifacts(Path jmxScript, Path csvData) {
        this(jmxScript, csvData, "", "");
    }

    /** @return the digests keyed by filename, as a provenance manifest records them. */
    public Map<String, String> digestsByFilename() {
        if (jmxDigest.isEmpty() && csvDigest.isEmpty()) {
            return Map.of();
        }
        return Map.of(
                jmxScript.getFileName().toString(), jmxDigest,
                csvData.getFileName().toString(), csvDigest);
    }
}
