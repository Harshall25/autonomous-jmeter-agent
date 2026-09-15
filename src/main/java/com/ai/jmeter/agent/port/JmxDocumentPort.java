package com.ai.jmeter.agent.port;

import com.ai.jmeter.agent.domain.jmx.JmxMutation;
import com.ai.jmeter.agent.domain.jmx.JmxStructure;
import com.ai.jmeter.agent.domain.jmx.JmxValidationResult;
import java.util.List;

/**
 * Driven port: treats a JMeter plan as a document tree rather than a string.
 *
 * <p>Gives the core three capabilities it cannot get from a language model reliably — apply a
 * precise edit, check a plan is sound before spending an execution on it, and summarize what a
 * plan actually does.
 */
public interface JmxDocumentPort {

    /**
     * Applies mutations in order, each to the result of the last.
     *
     * @param jmx       the plan to edit
     * @param mutations the edits to apply
     * @return the rewritten plan
     * @throws JmxDocumentException if the plan cannot be parsed or a mutation cannot be applied
     */
    String apply(String jmx, List<JmxMutation> mutations);

    /**
     * Checks a plan for defects that would waste an execution attempt.
     *
     * @param jmx the plan to check
     * @return errors and warnings; never throws for a malformed plan, that is an error result
     */
    JmxValidationResult validate(String jmx);

    /**
     * Summarizes a plan's samplers and variable flow.
     *
     * @param jmx the plan to describe
     * @return the structural summary
     * @throws JmxDocumentException if the plan cannot be parsed
     */
    JmxStructure describe(String jmx);
}
