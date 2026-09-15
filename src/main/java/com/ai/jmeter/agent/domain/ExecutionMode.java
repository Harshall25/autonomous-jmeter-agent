package com.ai.jmeter.agent.domain;

/**
 * The ingestion strategies the agent supports.
 *
 * <p>The mode is chosen by the operator up-front and travels through the whole agentic workflow:
 * it selects which {@code TrafficParserPort} implementation consumes the source file, and which
 * system prompt briefs the model.
 *
 * <p>A mode names where the workload description comes from, not which protocol the generated plan
 * speaks. Those are separate concerns: a HAR capture can contain REST, GraphQL and WebSocket
 * traffic at once, and the parser labels each request so the model picks the right sampler per
 * call rather than per run.
 */
public enum ExecutionMode {

    /**
     * An HTTP Archive ({@code .har}) capture of real traffic. The richest source, because it
     * shows what actually happened including the correlation the plan has to reproduce.
     */
    API("HTTP Archive (.har) capture of real traffic", PromptProfile.HTTP),

    /**
     * A database slow-query or trace log, producing a JDBC-sampler plan with the {@code WHERE}
     * clause literals parameterized.
     */
    SQL("Database slow query / trace log", PromptProfile.JDBC),

    /**
     * An OpenAPI / Swagger document. Available at design time, long before anyone has captured
     * production traffic, which is what lets the agent shift left.
     */
    OPENAPI("OpenAPI / Swagger specification", PromptProfile.HTTP),

    /** A Postman collection, usually the artifact a QA team already maintains by hand. */
    POSTMAN("Postman collection export", PromptProfile.HTTP),

    /**
     * A manifest of broker topics and message shapes, producing a plan that asserts on consumer
     * lag and propagation rather than on response codes.
     */
    STREAMING("Event streaming topic manifest", PromptProfile.STREAMING);

    /** Which family of samplers a mode's plan is built from, and therefore which prompt applies. */
    public enum PromptProfile {
        HTTP, JDBC, STREAMING
    }

    private final String description;
    private final PromptProfile promptProfile;

    ExecutionMode(String description, PromptProfile promptProfile) {
        this.description = description;
        this.promptProfile = promptProfile;
    }

    public String description() {
        return description;
    }

    public PromptProfile promptProfile() {
        return promptProfile;
    }

    /**
     * @return {@code true} when the generated plan is driven by JDBC samplers, which additionally
     * requires a driver on the JMeter classpath
     */
    public boolean requiresJdbcDriver() {
        return promptProfile == PromptProfile.JDBC;
    }
}
