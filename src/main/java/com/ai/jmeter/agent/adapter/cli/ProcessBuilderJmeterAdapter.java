package com.ai.jmeter.agent.adapter.cli;

import com.ai.jmeter.agent.domain.ExecutionReport;
import com.ai.jmeter.agent.port.ExecutionEngineException;
import com.ai.jmeter.agent.port.ExecutionEnginePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: runs a generated plan through the JMeter CLI in non-GUI mode.
 *
 * <p>Invokes {@code jmeter -n -t <plan> -l <results>} and turns what comes back into an
 * {@link ExecutionReport}. This is the agent's only source of ground truth — everything the
 * healing loop believes about a plan's health originates here.
 */
public final class ProcessBuilderJmeterAdapter implements ExecutionEnginePort {

    private static final Logger log = LoggerFactory.getLogger(ProcessBuilderJmeterAdapter.class);

    private static final String RESULTS_FILE = "results.jtl";
    private static final String RUN_LOG_FILE = "jmeter-run.log";
    private static final String JMETER_LOG_FILE = "jmeter.log";
    private static final String JMETER_EXECUTABLE = "jmeter";

    private final ProcessRunner processRunner;
    private final JtlResultParser jtlResultParser;
    private final Path jmeterBinDirectory;
    private final Path workspaceDirectory;
    private final Duration executionTimeout;
    private final int maxProcessOutputCharacters;

    public ProcessBuilderJmeterAdapter(
            ProcessRunner processRunner,
            JtlResultParser jtlResultParser,
            Path jmeterBinDirectory,
            Path workspaceDirectory,
            Duration executionTimeout,
            int maxProcessOutputCharacters) {
        this.processRunner = processRunner;
        this.jtlResultParser = jtlResultParser;
        this.jmeterBinDirectory = jmeterBinDirectory;
        this.workspaceDirectory = workspaceDirectory;
        this.executionTimeout = executionTimeout;
        this.maxProcessOutputCharacters = maxProcessOutputCharacters;
    }

    @Override
    public ExecutionReport execute(Path jmxScript) {
        Path resultsFile = workspaceDirectory.resolve(RESULTS_FILE);
        Path runLog = workspaceDirectory.resolve(RUN_LOG_FILE);

        // Each healing turn re-runs in the same workspace. JMeter refuses to overwrite an existing
        // results file, and a stale one would be read as this run's evidence — so clear it first.
        deleteIfPresent(resultsFile);

        List<String> command = List.of(
                jmeterBinDirectory.resolve(JMETER_EXECUTABLE).toString(),
                "-n",
                "-t", jmxScript.toString(),
                "-l", resultsFile.toString(),
                "-j", workspaceDirectory.resolve(JMETER_LOG_FILE).toString(),
                "-f");

        log.info("Executing JMeter: {}", String.join(" ", command));
        ProcessOutcome outcome = processRunner.run(
                command, workspaceDirectory, runLog, executionTimeout);
        String processOutput = readTail(runLog);
        JtlAnalysis analysis = jtlResultParser.parse(resultsFile);

        return interpret(outcome, analysis, processOutput);
    }

    /**
     * Turns the raw signals into a verdict.
     *
     * <p>Order matters. A timeout invalidates whatever partial results were written. Failing
     * samples are reported ahead of a non-zero exit code because they carry far better evidence
     * for the healing turn — the exit code alone says only that something went wrong.
     */
    private ExecutionReport interpret(
            ProcessOutcome outcome, JtlAnalysis analysis, String processOutput) {
        if (outcome.timedOut()) {
            log.error("JMeter exceeded its {} budget and was terminated", executionTimeout);
            return ExecutionReport.processFailure(
                    "JMeter timed out after %s and was terminated.%n%s"
                            .formatted(executionTimeout, processOutput));
        }
        if (!analysis.failures().isEmpty()) {
            log.warn("{} of {} samples failed", analysis.failures().size(), analysis.totalSamples());
            return ExecutionReport.sampleFailures(
                    analysis.totalSamples(), analysis.failures(), processOutput);
        }
        if (outcome.exitCode() != 0) {
            log.error("JMeter exited with status {}", outcome.exitCode());
            return ExecutionReport.processFailure(
                    "JMeter exited with status %d.%n%s".formatted(outcome.exitCode(), processOutput));
        }
        if (analysis.totalSamples() == 0) {
            log.error("JMeter completed but recorded no samples");
            return ExecutionReport.noSamples(processOutput);
        }
        return ExecutionReport.success(analysis.totalSamples(), processOutput);
    }

    private void deleteIfPresent(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            throw new ExecutionEngineException("Unable to clear stale results file: " + file, e);
        }
    }

    /**
     * @return the tail of the run log, or an empty string when nothing was captured. The tail is
     * what matters: JMeter reports its errors last.
     */
    private String readTail(Path logFile) {
        if (!Files.exists(logFile)) {
            return "";
        }
        try {
            String content = new String(Files.readAllBytes(logFile), StandardCharsets.UTF_8);
            if (content.length() <= maxProcessOutputCharacters) {
                return content;
            }
            return "...[earlier output truncated]\n"
                    + content.substring(content.length() - maxProcessOutputCharacters);
        } catch (IOException e) {
            log.warn("Could not read JMeter run log {}", logFile, e);
            return "<JMeter run log at %s could not be read>".formatted(logFile);
        }
    }
}
