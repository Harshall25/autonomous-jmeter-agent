package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.memory.HealPrecedent;
import java.util.List;

/**
 * Driven port: the agent's memory of repairs it has made before.
 *
 * <p>Without it every run starts cold and re-derives "extract the bearer token from /login" from
 * first principles, at full frontier-model cost, for every service in the estate. With it, the
 * second encounter with a familiar failure is a first-attempt success, and the value compounds
 * across an organization's whole test suite rather than being thrown away at the end of each run.
 *
 * <p>The contract is deliberately retrieval-shaped rather than key-value: failures are similar
 * far more often than they are identical, so an implementation is expected to rank by similarity.
 */
public interface HealMemoryPort {

    /**
     * Finds past repairs for failures resembling this one.
     *
     * @param signature the fingerprint of the current failure
     * @param limit     how many precedents to return at most
     * @return the closest matches, most similar first; empty when nothing resembles it
     */
    List<HealPrecedent> recall(String signature, int limit);

    /**
     * Records the outcome of a repair, including one that failed.
     *
     * <p>Failed repairs are worth as much as successful ones: telling the model what has already
     * been tried and did not work stops it proposing the same edit on the next encounter.
     *
     * @param precedent what was tried, and whether it worked
     */
    void remember(HealPrecedent precedent);
}
