package com.ai.jmeter.agent.adapter.fs;

import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.port.WorkspaceException;
import com.ai.jmeter.agent.port.WorkspacePort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: materializes generated plans into the workspace directory JMeter reads from.
 *
 * <p>Artifacts are written to stable filenames and overwritten on every healing turn, so the
 * workspace always holds the plan that was last executed. That is what makes a failed run
 * debuggable after the fact: whatever is on disk is exactly what JMeter saw.
 */
public final class FileSystemWorkspaceAdapter implements WorkspacePort {

    private static final Logger log = LoggerFactory.getLogger(FileSystemWorkspaceAdapter.class);

    private static final String JMX_FILENAME = "auto_test.jmx";
    private static final String CSV_FILENAME = "test_data.csv";

    /** Filename fragments that identify a JDBC driver JAR across the common vendors. */
    private static final List<String> JDBC_JAR_TOKENS = List.of(
            "jdbc", "connector", "mysql", "postgresql", "ojdbc", "sqlserver", "mariadb", "db2");

    private final Path workspaceDirectory;
    private final Path jmeterLibDirectory;

    public FileSystemWorkspaceAdapter(Path workspaceDirectory, Path jmeterLibDirectory) {
        this.workspaceDirectory = workspaceDirectory;
        this.jmeterLibDirectory = jmeterLibDirectory;
    }

    @Override
    public WorkspaceArtifacts write(JmeterGenerationResult result) {
        try {
            Files.createDirectories(workspaceDirectory);
            Path jmxScript = workspaceDirectory.resolve(JMX_FILENAME);
            Path csvData = workspaceDirectory.resolve(CSV_FILENAME);

            Files.writeString(jmxScript, result.jmxXmlContent(), StandardCharsets.UTF_8);
            Files.writeString(csvData, result.csvTemplateContent(), StandardCharsets.UTF_8);

            log.info("Wrote test plan to {} and test data to {}", jmxScript, csvData);
            return new WorkspaceArtifacts(jmxScript, csvData);
        } catch (IOException e) {
            throw new WorkspaceException(
                    "Unable to write generated artifacts to " + workspaceDirectory, e);
        }
    }

    @Override
    public boolean jdbcDriverAvailable() {
        try (Stream<Path> libraryFiles = Files.list(jmeterLibDirectory)) {
            return libraryFiles.anyMatch(FileSystemWorkspaceAdapter::isJdbcDriverJar);
        } catch (IOException e) {
            // An unreadable or absent lib directory is indistinguishable from one without a
            // driver, and both lead to the same operator warning rather than a hard failure.
            log.debug("Could not inspect JMeter lib directory {}", jmeterLibDirectory, e);
            return false;
        }
    }

    private static boolean isJdbcDriverJar(Path file) {
        String filename = file.getFileName().toString().toLowerCase();
        return filename.endsWith(".jar") && JDBC_JAR_TOKENS.stream().anyMatch(filename::contains);
    }
}
