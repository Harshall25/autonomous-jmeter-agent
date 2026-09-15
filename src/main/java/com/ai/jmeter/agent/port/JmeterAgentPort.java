package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;

/**
 * Driven port: the reasoning half of the agent.
 *
 * <p>Two operations, matching the two things the agent ever asks a model to do — author a plan
 * from observed traffic, and repair a plan that failed. Keeping both behind one interface lets
 * the orchestrator drive the full loop without knowing an LLM exists.
 */
public interface JmeterAgentPort {

    /**
     * Authors a JMeter plan from minimized traffic.
     *
     * @param parsedTraffic the parser output for the run
     * @param mode          selects the system prompt: HTTP samplers versus JDBC samplers
     * @return the generated plan and its test data
     * @throws JmeterAgentException if the model cannot be reached or returns an unusable answer
     */
    JmeterGenerationResult generateScript(String parsedTraffic, ExecutionMode mode);

    /**
     * Repairs a plan that failed execution.
     *
     * @param currentScript the JMX that failed
     * @param errorLogs     the evidence digest from the failing run
     * @return a corrected plan and refreshed test data
     * @throws JmeterAgentException if the model cannot be reached or returns an unusable answer
     */
    JmeterGenerationResult healScript(String currentScript, String errorLogs);
}
