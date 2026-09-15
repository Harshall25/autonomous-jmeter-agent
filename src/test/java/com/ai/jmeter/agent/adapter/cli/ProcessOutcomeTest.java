package com.ai.jmeter.agent.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProcessOutcome")
class ProcessOutcomeTest {

    @Test
    @DisplayName("a clean exit is the only successful outcome")
    void cleanExitIsSuccessful() {
        assertThat(ProcessOutcome.completed(0).successful()).isTrue();
        assertThat(ProcessOutcome.completed(1).successful()).isFalse();
    }

    @Test
    @DisplayName("a killed process is never successful, whatever its exit code reads as")
    void timedOutIsNeverSuccessful() {
        ProcessOutcome outcome = ProcessOutcome.killedOnTimeout();

        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.exitCode()).isEqualTo(ProcessOutcome.TIMED_OUT_EXIT_CODE);
    }
}
