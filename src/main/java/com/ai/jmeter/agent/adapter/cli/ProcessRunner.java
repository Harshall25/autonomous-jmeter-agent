package com.ai.jmeter.agent.adapter.cli;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * A seam over external process launching.
 *
 * <p>Exists so the JMeter adapter's decision logic — how exit codes, timeouts and result files
 * combine into a verdict — can be exercised without spawning a real JMeter installation. The
 * production implementation is the only part that actually needs a JVM with a process table.
 */
public interface ProcessRunner {

    /**
     * Runs a command to completion, or kills it once the budget expires.
     *
     * @param command          the command and its arguments
     * @param workingDirectory the directory to run in
     * @param outputLog        file receiving the merged stdout and stderr
     * @param timeout          how long the process may run before it is destroyed
     * @return how the process terminated
     */
    ProcessOutcome run(List<String> command, Path workingDirectory, Path outputLog, Duration timeout);
}
