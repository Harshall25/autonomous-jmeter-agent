package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.governance.RunManifest;
import com.ai.jmeter.agent.domain.governance.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: the immutable record of what each run was, who asked for it and who approved it.
 *
 * <p>Append-only by contract. An implementation that lets a manifest be replaced would defeat the
 * signature it stores, since the point of both is that the record months later is the record that
 * was written at the time.
 */
public interface ProvenanceStorePort {

    /**
     * @param manifest the signed manifest to file
     * @throws ProvenanceException if it cannot be persisted
     */
    void record(RunManifest manifest);

    /**
     * @param runId the run to attest to
     * @return its manifest, or empty when no such run was recorded
     */
    Optional<RunManifest> find(String runId);

    /**
     * @param tenant the organization asking
     * @param limit  how many manifests to return
     * @return that tenant's manifests, newest first, and never another tenant's
     */
    List<RunManifest> forTenant(TenantId tenant, int limit);
}
