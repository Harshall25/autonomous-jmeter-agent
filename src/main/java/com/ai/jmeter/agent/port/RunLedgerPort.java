package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.controlplane.RunLedgerEntry;
import com.ai.jmeter.agent.domain.governance.TenantId;
import java.util.List;
import java.util.Optional;

/**
 * Driven port: the durable record the control plane reads runs back from.
 *
 * <p>Separate from {@link ResultStorePort} because the two answer different questions and would
 * otherwise be one store serving neither well. The result store keeps measurements, is queried by
 * plan, and is what regression detection is built on. The ledger keeps the account of a run —
 * which model turn changed what, and why — and is queried by run id, by a person asking whether
 * they trust what the agent did.
 */
public interface RunLedgerPort {

    /**
     * Files one completed run.
     *
     * @param entry the account of the run
     * @throws ResultStoreException if the entry cannot be persisted
     */
    void record(RunLedgerEntry entry);

    /**
     * @param tenant the organization asking
     * @param limit  how many runs to return
     * @return that tenant's most recently recorded runs, newest first
     */
    List<RunLedgerEntry> recent(TenantId tenant, int limit);

    /**
     * @param tenant the organization asking
     * @param runId  the run to look up
     * @return the run, or empty when this tenant has no such run — which is also the answer when
     * another tenant does, so that a run id cannot be probed for
     */
    Optional<RunLedgerEntry> find(TenantId tenant, String runId);
}
