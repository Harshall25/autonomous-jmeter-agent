package com.ai.jmeter.agent.domain.governance;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The immutable, attestable record of why a given test plan existed and who stood behind it.
 *
 * <p>An LLM in the loop turns provenance from a nicety into a control. A regulated organization
 * has to be able to answer, months later, which model version and which prompt revision produced
 * the plan that was run against production, who started it, and whether anyone approved it — and
 * to show that the answer has not been edited since.
 *
 * <p>Signing is over {@link #canonicalPayload()}, a deterministic rendering of every field that
 * matters. Maps are ordered and every value is included, so the same manifest always produces the
 * same bytes and changing any of them invalidates the signature.
 *
 * @param runId           the run this attests to
 * @param tenant          the organization it was run for
 * @param startedBy       the principal who asked for it
 * @param approvedBy      who attested to the plan; empty when nobody has
 * @param promptRevision  which revision of the agent's prompts produced the plan
 * @param modelsByTurn    which model answered each kind of turn
 * @param artifactDigests digest per produced artifact, so the plan on disk can be matched back
 * @param recordedAt      when the run finished
 * @param signature       the signature over the canonical payload; empty until signed
 */
public record RunManifest(
        String runId,
        TenantId tenant,
        String startedBy,
        String approvedBy,
        String promptRevision,
        Map<String, String> modelsByTurn,
        Map<String, String> artifactDigests,
        Instant recordedAt,
        String signature) {

    public RunManifest {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("A manifest must name the run it attests to");
        }
        if (tenant == null) {
            throw new IllegalArgumentException("A manifest must name the tenant it belongs to");
        }
        startedBy = startedBy == null ? "" : startedBy;
        approvedBy = approvedBy == null ? "" : approvedBy;
        promptRevision = promptRevision == null ? "" : promptRevision;
        modelsByTurn = modelsByTurn == null ? Map.of() : Map.copyOf(modelsByTurn);
        artifactDigests = artifactDigests == null ? Map.of() : Map.copyOf(artifactDigests);
        signature = signature == null ? "" : signature;
    }

    /**
     * @return an unsigned manifest, ready to be handed to a signer
     */
    public static RunManifest unsigned(
            String runId,
            TenantId tenant,
            String startedBy,
            String promptRevision,
            Map<String, String> modelsByTurn,
            Map<String, String> artifactDigests,
            Instant recordedAt) {
        return new RunManifest(runId, tenant, startedBy, "", promptRevision,
                modelsByTurn, artifactDigests, recordedAt, "");
    }

    /**
     * The bytes a signature covers.
     *
     * <p>Ordered and fully expanded rather than a {@code toString()}: a canonical form whose
     * output depends on map iteration order would produce signatures that fail to verify on a
     * different JVM run, and one that omitted a field would let that field be edited freely.
     *
     * @return the deterministic rendering of everything but the signature itself
     */
    public String canonicalPayload() {
        return String.join("\n", List.of(
                "runId=" + runId,
                "tenant=" + tenant.value(),
                "startedBy=" + startedBy,
                "approvedBy=" + approvedBy,
                "promptRevision=" + promptRevision,
                "models=" + ordered(modelsByTurn),
                "artifacts=" + ordered(artifactDigests),
                "recordedAt=" + recordedAt));
    }

    /** @return this manifest with a signature attached. */
    public RunManifest signedWith(String newSignature) {
        return new RunManifest(runId, tenant, startedBy, approvedBy, promptRevision,
                modelsByTurn, artifactDigests, recordedAt, newSignature);
    }

    /**
     * Records an approval, which invalidates any existing signature because the approver is part
     * of what is attested to. The caller re-signs.
     *
     * @param approver the principal attesting to the plan
     * @return an unsigned manifest carrying the approval
     */
    public RunManifest approvedBy(String approver) {
        return new RunManifest(runId, tenant, startedBy, approver, promptRevision,
                modelsByTurn, artifactDigests, recordedAt, "");
    }

    public boolean isSigned() {
        return !signature.isBlank();
    }

    public boolean isApproved() {
        return !approvedBy.isBlank();
    }

    public String describe() {
        return """
                Run %s (tenant %s)
                  Started by  : %s
                  Approved by : %s
                  Prompts     : %s
                  Models      : %s
                  Artifacts   : %s
                  Recorded    : %s
                  Signature   : %s""".formatted(
                runId, tenant, startedBy,
                isApproved() ? approvedBy : "nobody",
                promptRevision.isBlank() ? "unrecorded" : promptRevision,
                ordered(modelsByTurn),
                ordered(artifactDigests),
                recordedAt,
                isSigned() ? signature : "unsigned");
    }

    private static String ordered(Map<String, String> values) {
        return new TreeMap<>(values).toString();
    }
}
