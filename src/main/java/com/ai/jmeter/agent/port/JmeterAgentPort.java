package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.jmx.JmxRepairPlan;

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
     * Asks for a repair expressed as structural edits rather than as a rewritten plan.
     *
     * <p>Preferred over {@link #healScript} on every healing turn: the reply is a short list of
     * operations instead of thousands of lines of XML, which is cheaper, faster, reviewable as a
     * diff, and cannot produce malformed markup.
     *
     * @param structureSummary what the plan currently does and how its variables flow
     * @param errorLogs        the evidence digest from the failing run
     * @return the proposed edits, or a signal that the plan needs rewriting outright
     * @throws JmeterAgentException if the model cannot be reached or returns an unusable answer
     */
    JmxRepairPlan proposeRepairs(String structureSummary, String errorLogs);

    /**
     * Regenerates a failing plan from scratch.
     *
     * <p>The fallback when {@link #proposeRepairs} cannot express the fix, or when the plan is too
     * malformed to patch.
     *
     * @param currentScript the JMX that failed
     * @param errorLogs     the evidence digest from the failing run
     * @return a corrected plan and refreshed test data
     * @throws JmeterAgentException if the model cannot be reached or returns an unusable answer
     */
    JmeterGenerationResult healScript(String currentScript, String errorLogs);
}
