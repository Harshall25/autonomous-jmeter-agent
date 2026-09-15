package com.ai.jmeter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import com.ai.jmeter.agent.adapter.cli.AgentCommandLineRunner;
import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.orchestrator.SelfHealingOrchestrator;
import com.ai.jmeter.agent.orchestrator.TrafficParserRegistry;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import com.ai.jmeter.agent.port.JmeterAgentPort;
import com.ai.jmeter.agent.port.WorkspacePort;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Boots the real application context to prove the hexagon is fully wired.
 *
 * <p>No model call is made: the context is built and inspected, and the command line runner
 * prints usage rather than starting a run because no arguments are supplied.
 */
@SpringBootTest(properties = {
        "spring.ai.anthropic.api-key=test-key-not-used",
        "agent.jmeter.home-path=/tmp/jmeter-under-test/bin",
        "agent.jmeter.workspace=target/test-workspace"
})
@DisplayName("Application context")
class JmeterAgentApplicationTests {

    @Autowired
    private SelfHealingOrchestrator orchestrator;

    @Autowired
    private TrafficParserRegistry parserRegistry;

    @Autowired
    private JmeterAgentPort agentPort;

    @Autowired
    private ExecutionEnginePort executionEnginePort;

    @Autowired
    private WorkspacePort workspacePort;

    @Autowired
    private AgentCommandLineRunner commandLineRunner;

    @Test
    @DisplayName("binds every port and assembles the orchestrator")
    void contextLoads() {
        assertThat(orchestrator).isNotNull();
        assertThat(agentPort).isNotNull();
        assertThat(executionEnginePort).isNotNull();
        assertThat(workspacePort).isNotNull();
        assertThat(commandLineRunner).isNotNull();
    }

    @Test
    @DisplayName("routes both ingestion modes to a registered parser")
    void bothModesAreParseable() {
        assertThat(parserRegistry.parserFor(ExecutionMode.API).supportedMode())
                .isEqualTo(ExecutionMode.API);
        assertThat(parserRegistry.parserFor(ExecutionMode.SQL).supportedMode())
                .isEqualTo(ExecutionMode.SQL);
    }
}
