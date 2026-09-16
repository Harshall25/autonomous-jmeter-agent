package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.virtualization.StubbedDependency;
import com.ai.jmeter.agent.domain.virtualization.VirtualizedEnvironment;
import java.util.List;

/**
 * Driven port: stands in for the dependencies a load test must not actually hammer.
 *
 * <p>Two problems, one mechanism. Shared performance environments are the commonest reason a
 * result cannot be reproduced — someone else's deploy lands mid-run and the numbers move for
 * reasons nobody can reconstruct. And third-party dependencies have rate limits that make
 * realistic load impossible, so teams quietly test at a tenth of production volume and discover
 * the truth in production.
 *
 * <p>Stubbing also makes latency a controlled variable rather than an observed one: a dependency
 * that can be told to take 300ms lets a team measure how their system behaves when it does,
 * before it does.
 */
public interface ServiceVirtualizationPort {

    /**
     * Materializes stubs for the given dependencies.
     *
     * @param dependencies what to stand in for, and how each should behave
     * @return where the stubs were written and how to run them
     * @throws VirtualizationException if the stubs cannot be materialized
     */
    VirtualizedEnvironment virtualize(List<StubbedDependency> dependencies);
}
