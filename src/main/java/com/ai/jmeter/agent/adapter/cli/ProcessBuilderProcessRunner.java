package com.ai.jmeter.agent.adapter.cli;

import com.ai.jmeter.agent.port.ExecutionEngineException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Launches external processes with {@link ProcessBuilder}.
 *
 * <p>Output is redirected straight to a file rather than drained through a pipe. A JMeter run can
 * emit a lot of output, and a full pipe buffer would deadlock the process against a parent that
 * is busy waiting for it to exit — the classic way an unattended agent hangs forever.
 */
public final class ProcessBuilderProcessRunner implements ProcessRunner {

    @Override
    public ProcessOutcome run(
            List<String> command, Path workingDirectory, Path outputLog, Duration timeout) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(workingDirectory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(outputLog.toFile())
                    .start();

            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return ProcessOutcome.killedOnTimeout();
            }
            return ProcessOutcome.completed(process.exitValue());
        } catch (IOException e) {
            throw new ExecutionEngineException("Unable to start process: " + command, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ExecutionEngineException("Interrupted while awaiting process: " + command, e);
        }
    }
}
