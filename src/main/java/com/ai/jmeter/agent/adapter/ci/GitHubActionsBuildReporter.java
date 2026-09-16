package com.ai.jmeter.agent.adapter.ci;

import com.ai.jmeter.agent.domain.ci.GateVerdict;
import com.ai.jmeter.agent.port.BuildReportException;
import com.ai.jmeter.agent.port.BuildReporterPort;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: publishes the gate's report the way a pipeline can consume it.
 *
 * <p>Three outputs, because a CI report has three audiences. The Markdown file is what a workflow
 * step posts as a pull request comment, where the reviewer reads it. The step summary renders the
 * same table on the run's own page in GitHub Actions. The workflow commands surface each breach
 * as an annotation against the check, so a failing gate says what it objected to without anyone
 * opening the log.
 *
 * <p>It deliberately does not call the GitHub API. Posting a comment needs a token with write
 * access to the repository, and an agent that holds one in every CI container is a far larger
 * security surface than a file a workflow step posts with the token it already has.
 */
public final class GitHubActionsBuildReporter implements BuildReporterPort {

    private static final Logger log = LoggerFactory.getLogger(GitHubActionsBuildReporter.class);

    private final Path reportFile;
    private final Path stepSummaryFile;
    private final PrintStream console;

    /**
     * @param reportFile      where to write the Markdown a pipeline step posts
     * @param stepSummaryFile GitHub Actions' step summary file, or null when not running there
     * @param console         where workflow commands are written; stdout in production, because
     *                        the runner reads them from the process's own output stream
     */
    public GitHubActionsBuildReporter(
            Path reportFile, Path stepSummaryFile, PrintStream console) {
        this.reportFile = reportFile;
        this.stepSummaryFile = stepSummaryFile;
        this.console = console;
    }

    @Override
    public void publish(GateVerdict verdict, String comment) {
        try {
            write(reportFile, comment, StandardOpenOption.TRUNCATE_EXISTING);
            log.info("Performance report written to {}", reportFile);

            if (stepSummaryFile != null) {
                write(stepSummaryFile, comment, StandardOpenOption.APPEND);
            }
        } catch (IOException e) {
            throw new BuildReportException("Unable to publish the performance report", e);
        }
        annotate(verdict);
    }

    private static void write(Path file, String content, StandardOpenOption mode)
            throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Files.writeString(file, content + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode);
    }

    /**
     * Emits one annotation per breach, so the check itself carries the reason.
     *
     * <p>Written raw to the console rather than through the logger: the runner only recognizes a
     * workflow command on a line of its own, and a logging pattern prefixes every line.
     */
    private void annotate(GateVerdict verdict) {
        if (verdict.passed()) {
            console.println("::notice title=Performance gate::No performance regressions detected");
            return;
        }
        verdict.reasons().forEach(reason ->
                console.println("::error title=Performance gate::" + escape(reason)));
    }

    /**
     * Workflow commands are newline-delimited and use {@code ::} as their own delimiter, so an
     * unescaped message would truncate the annotation or split it into several.
     */
    private static String escape(String message) {
        return message
                .replace("%", "%25")
                .replace("\r", "%0D")
                .replace("\n", "%0A")
                .replace("::", "%3A%3A");
    }
}
