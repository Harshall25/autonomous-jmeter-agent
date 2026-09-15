package com.ai.jmeter.agent.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.port.TrafficParserPort;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("TrafficParserRegistry")
class TrafficParserRegistryTest {

    private static TrafficParserPort parserFor(ExecutionMode mode) {
        TrafficParserPort parser = mock(TrafficParserPort.class);
        when(parser.supportedMode()).thenReturn(mode);
        return parser;
    }

    @Test
    @DisplayName("routes each mode to the parser that claims it")
    void routesByMode() {
        TrafficParserPort har = parserFor(ExecutionMode.API);
        TrafficParserPort sql = parserFor(ExecutionMode.SQL);

        TrafficParserRegistry registry = new TrafficParserRegistry(List.of(har, sql));

        assertThat(registry.parserFor(ExecutionMode.API)).isSameAs(har);
        assertThat(registry.parserFor(ExecutionMode.SQL)).isSameAs(sql);
    }

    @Test
    @DisplayName("refuses an ambiguous registry rather than routing unpredictably")
    void rejectsDuplicateModes() {
        List<TrafficParserPort> duplicates =
                List.of(parserFor(ExecutionMode.API), parserFor(ExecutionMode.API));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> new TrafficParserRegistry(duplicates))
                .withMessageContaining("Duplicate parser registered for mode API");
    }

    @Test
    @DisplayName("reports a mode nothing can parse")
    void rejectsUnregisteredMode() {
        TrafficParserRegistry registry =
                new TrafficParserRegistry(List.of(parserFor(ExecutionMode.API)));

        assertThatIllegalStateException()
                .isThrownBy(() -> registry.parserFor(ExecutionMode.SQL))
                .withMessageContaining("No traffic parser registered for mode SQL");
    }
}
