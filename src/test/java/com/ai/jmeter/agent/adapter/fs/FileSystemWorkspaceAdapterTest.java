package com.ai.jmeter.agent.adapter.fs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.JmeterGenerationResult;
import com.ai.jmeter.agent.domain.WorkspaceArtifacts;
import com.ai.jmeter.agent.port.WorkspaceException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("FileSystemWorkspaceAdapter")
class FileSystemWorkspaceAdapterTest {

    @TempDir
    Path tempDir;

    private static JmeterGenerationResult plan(String jmx, String csv) {
        return new JmeterGenerationResult(jmx, csv, List.of("user"), "rationale");
    }

    @Nested
    @DisplayName("writing artifacts")
    class Writing {

        @Test
        @DisplayName("writes the plan and its data under the conventional filenames")
        void writesArtifacts() throws IOException {
            Path workspace = tempDir.resolve("workspace");
            FileSystemWorkspaceAdapter adapter =
                    new FileSystemWorkspaceAdapter(workspace, tempDir.resolve("lib"));

            WorkspaceArtifacts artifacts =
                    adapter.write(plan("<jmeterTestPlan/>", "user\nalice"));

            assertThat(artifacts.jmxScript()).isEqualTo(workspace.resolve("auto_test.jmx"));
            assertThat(artifacts.csvData()).isEqualTo(workspace.resolve("test_data.csv"));
            assertThat(Files.readString(artifacts.jmxScript())).isEqualTo("<jmeterTestPlan/>");
            assertThat(Files.readString(artifacts.csvData())).isEqualTo("user\nalice");
        }

        @Test
        @DisplayName("creates the workspace directory on first use")
        void createsWorkspaceDirectory() {
            Path nested = tempDir.resolve("deeply/nested/workspace");

            new FileSystemWorkspaceAdapter(nested, tempDir.resolve("lib"))
                    .write(plan("<plan/>", ""));

            assertThat(nested).isDirectory();
        }

        @Test
        @DisplayName("overwrites the previous attempt so the workspace holds what just ran")
        void overwritesPreviousAttempt() throws IOException {
            FileSystemWorkspaceAdapter adapter =
                    new FileSystemWorkspaceAdapter(tempDir, tempDir.resolve("lib"));

            adapter.write(plan("<plan>draft</plan>", "old"));
            WorkspaceArtifacts artifacts = adapter.write(plan("<plan>repaired</plan>", "new"));

            assertThat(Files.readString(artifacts.jmxScript())).isEqualTo("<plan>repaired</plan>");
            assertThat(Files.readString(artifacts.csvData())).isEqualTo("new");
        }

        @Test
        @DisplayName("reports a workspace that cannot be written to")
        void reportsUnwritableWorkspace() throws IOException {
            Path blockedByAFile = tempDir.resolve("blocked");
            Files.writeString(blockedByAFile, "not a directory");
            FileSystemWorkspaceAdapter adapter =
                    new FileSystemWorkspaceAdapter(blockedByAFile, tempDir.resolve("lib"));
            JmeterGenerationResult result = plan("<plan/>", "");

            assertThatThrownBy(() -> adapter.write(result))
                    .isInstanceOf(WorkspaceException.class)
                    .hasMessageContaining("Unable to write generated artifacts")
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("JDBC driver detection")
    class DriverDetection {

        private FileSystemWorkspaceAdapter adapterWithLib(Path lib) {
            return new FileSystemWorkspaceAdapter(tempDir.resolve("workspace"), lib);
        }

        @Test
        @DisplayName("finds a vendor driver JAR in the JMeter lib directory")
        void findsDriverJar() throws IOException {
            Path lib = Files.createDirectory(tempDir.resolve("lib"));
            Files.writeString(lib.resolve("mysql-connector-j-8.4.0.jar"), "");

            assertThat(adapterWithLib(lib).jdbcDriverAvailable()).isTrue();
        }

        @Test
        @DisplayName("ignores JARs that are not drivers")
        void ignoresUnrelatedJars() throws IOException {
            Path lib = Files.createDirectory(tempDir.resolve("lib"));
            Files.writeString(lib.resolve("commons-lang3.jar"), "");

            assertThat(adapterWithLib(lib).jdbcDriverAvailable()).isFalse();
        }

        @Test
        @DisplayName("ignores a driver-named file that is not a JAR")
        void ignoresNonJarFiles() throws IOException {
            Path lib = Files.createDirectory(tempDir.resolve("lib"));
            Files.writeString(lib.resolve("postgresql-driver.txt"), "");

            assertThat(adapterWithLib(lib).jdbcDriverAvailable()).isFalse();
        }

        @Test
        @DisplayName("treats an absent lib directory as no driver rather than an error")
        void absentLibDirectory() {
            assertThat(adapterWithLib(tempDir.resolve("no-such-lib")).jdbcDriverAvailable())
                    .isFalse();
        }
    }
}
