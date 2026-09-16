package com.ai.jmeter.agent.domain.virtualization;

import java.nio.file.Path;
import java.util.List;

/**
 * The stub set produced for one run, and how to bring it up.
 *
 * @param mappingsDirectory where the stub definitions were written
 * @param composeFile       a compose file that runs them
 * @param dependencies      what was stubbed
 * @param port              the port the stub server listens on
 */
public record VirtualizedEnvironment(
        Path mappingsDirectory, Path composeFile, List<StubbedDependency> dependencies, int port) {

    public VirtualizedEnvironment {
        dependencies = List.copyOf(dependencies);
    }

    /** @return the base URL the plan should point at instead of the real dependency. */
    public String baseUrl() {
        return "http://localhost:" + port;
    }

    public String describe() {
        return """
                Virtualized %d dependency(ies) on %s
                  Mappings: %s
                  Compose : %s
                %s""".formatted(
                dependencies.size(), baseUrl(), mappingsDirectory, composeFile,
                dependencies.stream()
                        .map(dependency -> "  - " + dependency.describe())
                        .collect(java.util.stream.Collectors.joining("\n")));
    }
}
