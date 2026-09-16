package com.ai.jmeter.agent.adapter.ai;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.ExecutionMode.PromptProfile;
import com.ai.jmeter.agent.domain.memory.HealPrecedent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.Resource;

/**
 * Loads and manages the agent's instruction sets as Spring AI {@link PromptTemplate}s.
 *
 * <p>Keeping the prompts in {@code .st} resources rather than string constants means they can be
 * reviewed, diffed and tuned without recompiling — prompt text is behaviour in an agentic system,
 * and behaviour belongs under version control in its own right.
 *
 * <p>System prompts are keyed by {@link PromptProfile} rather than by mode, because several
 * ingestion modes produce the same kind of plan: a HAR capture, an OpenAPI document and a Postman
 * collection all end up as HTTP samplers, and duplicating that guidance three times would let the
 * copies drift.
 */
public final class PromptCatalog {

    /**
     * Paired with the healing system prompt, which already carries the script and the logs. The
     * user turn only has to ask for the deliverable.
     */
    private static final String HEAL_INSTRUCTION =
            "Return the corrected JMeter test plan and its matching CSV data set.";

    private static final String REPAIR_INSTRUCTION =
            "Return the smallest set of structural edits that fixes this failure.";

    private final Map<PromptProfile, PromptTemplate> systemPrompts;
    private final PromptTemplate healPrompt;
    private final PromptTemplate repairPlanPrompt;
    private final PromptTemplate rootCausePrompt;

    public PromptCatalog(
            Resource httpSystemPrompt,
            Resource jdbcSystemPrompt,
            Resource streamingSystemPrompt,
            Resource healPrompt,
            Resource repairPlanPrompt,
            Resource rootCausePrompt) {
        this.systemPrompts = new EnumMap<>(PromptProfile.class);
        this.systemPrompts.put(PromptProfile.HTTP, new PromptTemplate(httpSystemPrompt));
        this.systemPrompts.put(PromptProfile.JDBC, new PromptTemplate(jdbcSystemPrompt));
        this.systemPrompts.put(PromptProfile.STREAMING, new PromptTemplate(streamingSystemPrompt));
        this.healPrompt = new PromptTemplate(healPrompt);
        this.repairPlanPrompt = new PromptTemplate(repairPlanPrompt);
        this.rootCausePrompt = new PromptTemplate(rootCausePrompt);
    }

    /**
     * @param mode the ingestion mode being run
     * @return the system instructions briefing the model for the kind of plan that mode produces
     */
    public String systemPromptFor(ExecutionMode mode) {
        // Returned via getTemplate() rather than render(): these prompts contain literal
        // ${variable_name} JMeter syntax that the model must receive verbatim, and the
        // StringTemplate renderer would try to resolve the inner braces as placeholders.
        return systemPrompts.get(mode.promptProfile()).getTemplate();
    }

    /**
     * Renders the self-healing brief, interpolating the failed plan and the run evidence.
     *
     * @param currentScript the JMX that failed
     * @param errorLogs     the digest of what went wrong
     * @return the system instructions for the repair turn
     */
    public String healSystemPrompt(String currentScript, String errorLogs) {
        return healPrompt.render(Map.of(
                "current_script", currentScript,
                "error_logs", errorLogs));
    }

    /** @return the user turn accompanying {@link #healSystemPrompt(String, String)}. */
    public String healInstruction() {
        return HEAL_INSTRUCTION;
    }

    /**
     * Renders the structured-repair brief.
     *
     * @param planStructure what the failing plan does and how its variables flow
     * @param errorLogs     the evidence digest from the failing run
     * @param precedents    past repairs for similar failures, possibly empty
     * @return the system instructions for the repair-proposal turn
     */
    public String repairSystemPrompt(
            String planStructure, String errorLogs, List<HealPrecedent> precedents) {
        return repairPlanPrompt.render(Map.of(
                "plan_structure", planStructure,
                "error_logs", errorLogs,
                "precedents", renderPrecedents(precedents)));
    }

    /**
     * Renders recalled repairs, or an explicit statement that there are none.
     *
     * <p>Never left blank: an empty section reads to a model as an omission it should fill in,
     * whereas "none on record" is information.
     */
    private String renderPrecedents(List<HealPrecedent> precedents) {
        if (precedents.isEmpty()) {
            return "None on record. Diagnose this failure from the evidence alone.";
        }
        return precedents.stream()
                .map(HealPrecedent::describe)
                .collect(Collectors.joining("\n\n"));
    }

    /** @return the user turn accompanying the repair-proposal brief. */
    public String repairInstruction() {
        return REPAIR_INSTRUCTION;
    }

    /**
     * Renders the diagnosis brief.
     *
     * @param runAnalysis what the run measured and how it compared to history
     * @return the system instructions for the root-cause turn
     */
    public String rootCauseSystemPrompt(String runAnalysis) {
        return rootCausePrompt.render(Map.of("run_analysis", runAnalysis));
    }
}
