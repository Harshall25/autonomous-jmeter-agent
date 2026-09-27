package com.ai.jmeter.agent.domain;

/**
 * The two ingestion strategies the agent supports.
 *
 * <p>The mode is chosen by the operator up-front and travels through the whole agentic
 * workflow: it selects which {@code TrafficParserPort} implementation consumes the source
 * file, and which system prompt the AI adapter uses to brief the model.
 */
public enum ExecutionMode {

    /**
     * Ingests an HTTP Archive ({@code .har}) capture of front-end traffic and produces an
     * HTTP-sampler based JMeter plan with correlation and parameterization applied.
     */
    API("HTTP Archive (.har) front-end traffic"),

    /**
     * Ingests a database slow-query / trace log and produces a JDBC-sampler based JMeter
     * plan with the {@code WHERE} clause literals parameterized.
     */
    SQL("Database slow query / trace log");

    private final String description;

    ExecutionMode(String description) {
        this.description = description;
    }

    public String description() {
        return description;
    }

    /**
     * @return {@code true} when the generated plan must be driven by JDBC samplers, which
     * additionally requires a JDBC driver to be present on the JMeter classpath.
     */
    public boolean requiresJdbcDriver() {
        return this == SQL;
    }
}
