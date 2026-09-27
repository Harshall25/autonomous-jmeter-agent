package com.ai.jmeter.agent.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.port.ExecutionEngineException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * Drives the real {@link ProcessBuilder} shim against short-lived shell commands, so the
 * termination paths an unattended agent depends on are verified rather than assumed.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
@DisplayName("ProcessBuilderProcessRunner")
class ProcessBuilderProcessRunnerTest {

    private final ProcessBuilderProcessRunner runner = new ProcessBuilderProcessRunner();

    @TempDir
    Path workingDirectory;

    @AfterEach
    void clearInterruptFlag() {
        Thread.interrupted();
    }

    private Path logFile() {
        return workingDirectory.resolve("process.log");
    }

    @Test
    @DisplayName("captures a successful command and its output")
    void runsSuccessfulCommand() throws IOException {
        ProcessOutcome outcome = runner.run(
                List.of("/bin/sh", "-c", "echo hello from jmeter"),
                workingDirectory,
                logFile(),
                Duration.ofSeconds(30));

        assertThat(outcome.successful()).isTrue();
        assertThat(outcome.exitCode()).isZero();
        assertThat(outcome.timedOut()).isFalse();
        assertThat(Files.readString(logFile())).contains("hello from jmeter");
    }

    @Test
    @DisplayName("reports the exit status of a failing command")
    void reportsFailureExitCode() {
        ProcessOutcome outcome = runner.run(
                List.of("/bin/sh", "-c", "echo broken >&2; exit 3"),
                workingDirectory,
                logFile(),
                Duration.ofSeconds(30));

        assertThat(outcome.exitCode()).isEqualTo(3);
        assertThat(outcome.successful()).isFalse();
    }

    @Test
    @DisplayName("merges stderr into the captured output")
    void mergesStandardError() throws IOException {
        runner.run(
                List.of("/bin/sh", "-c", "echo to-stderr >&2"),
                workingDirectory,
                logFile(),
                Duration.ofSeconds(30));

        assertThat(Files.readString(logFile())).contains("to-stderr");
    }

    @Test
    @DisplayName("kills a command that overruns its budget instead of hanging forever")
    void killsOverrunningCommand() {
        ProcessOutcome outcome = runner.run(
                List.of("/bin/sh", "-c", "sleep 30"),
                workingDirectory,
                logFile(),
                Duration.ofMillis(250));

        assertThat(outcome.timedOut()).isTrue();
        assertThat(outcome.successful()).isFalse();
        assertThat(outcome.exitCode()).isEqualTo(ProcessOutcome.TIMED_OUT_EXIT_CODE);
    }

    @Test
    @DisplayName("reports a command that cannot be started at all")
    void reportsUnstartableCommand() {
        List<String> missingBinary =
                List.of(workingDirectory.resolve("no-such-jmeter").toString(), "-n");

        assertThatThrownBy(() -> runner.run(
                missingBinary, workingDirectory, logFile(), Duration.ofSeconds(5)))
                .isInstanceOf(ExecutionEngineException.class)
                .hasMessageContaining("Unable to start process")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("restores the interrupt flag when the wait is interrupted")
    void propagatesInterruption() {
        Thread.currentThread().interrupt();

        assertThatThrownBy(() -> runner.run(
                List.of("/bin/sh", "-c", "sleep 5"),
                workingDirectory,
                logFile(),
                Duration.ofSeconds(30)))
                .isInstanceOf(ExecutionEngineException.class)
                .hasMessageContaining("Interrupted while awaiting process")
                .hasCauseInstanceOf(InterruptedException.class);

        assertThat(Thread.currentThread().isInterrupted())
                .as("swallowing the interrupt would strand a shutdown")
                .isTrue();
    }
}
