package com.ai.jmeter.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Enforces the dependency rule the whole design rests on: the inside of the hexagon must not
 * know about the outside.
 *
 * <p>Nothing else in a Java build stops someone dropping a {@code @Service} onto a domain record
 * or reaching for Jackson inside the orchestrator. The compiler is perfectly happy either way,
 * and the architecture erodes one convenient import at a time. This test makes that erosion a
 * build failure.
 */
@DisplayName("Hexagonal architecture")
class HexagonalArchitectureTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/ai/jmeter/agent");

    /** Frameworks that must never appear inside the hexagon. */
    private static final List<String> FRAMEWORK_PACKAGES =
            List.of("org.springframework", "com.fasterxml.jackson", "jakarta.");

    private static Map<Path, List<String>> importsUnder(String layer) {
        try (Stream<Path> sources = Files.walk(SOURCE_ROOT.resolve(layer))) {
            return sources
                    .filter(path -> path.toString().endsWith(".java"))
                    .collect(java.util.stream.Collectors.toMap(
                            path -> path,
                            HexagonalArchitectureTest::readImports));
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read layer " + layer, e);
        }
    }

    private static List<String> readImports(Path source) {
        try {
            return Files.readAllLines(source, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> line.startsWith("import "))
                    .map(line -> line.substring("import ".length()).replace(";", ""))
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + source, e);
        }
    }

    private static void assertLayerIsFrameworkFree(String layer, List<String> forbidden) {
        importsUnder(layer).forEach((source, imports) -> assertThat(imports)
                .as("%s must not depend on a framework", source)
                .noneSatisfy(imported -> assertThat(forbidden)
                        .anySatisfy(prefix -> assertThat(imported).startsWith(prefix))));
    }

    @Test
    @DisplayName("the domain layer is pure Java")
    void domainIsPureJava() {
        // Also excludes SLF4J: domain types are values, and values do not log.
        assertLayerIsFrameworkFree("domain",
                Stream.concat(FRAMEWORK_PACKAGES.stream(), Stream.of("org.slf4j")).toList());
    }

    @Test
    @DisplayName("the domain layer depends on nothing but the JDK and itself")
    void domainDependsOnlyOnTheJdk() {
        importsUnder("domain").forEach((source, imports) -> assertThat(imports)
                .as("%s should import only JDK types or other domain types", source)
                .allSatisfy(imported -> assertThat(imported)
                        .satisfiesAnyOf(
                                dependency -> assertThat(dependency).startsWith("java."),
                                dependency -> assertThat(dependency)
                                        .startsWith("com.ai.jmeter.agent.domain."))));
    }

    @Test
    @DisplayName("the port layer declares contracts without framework types")
    void portsAreFrameworkFree() {
        assertLayerIsFrameworkFree("port",
                Stream.concat(FRAMEWORK_PACKAGES.stream(), Stream.of("org.slf4j")).toList());
    }

    @Test
    @DisplayName("the port layer contains only interfaces and the exceptions they declare")
    void portsDeclareOnlyContracts() {
        importsUnder("port").keySet().forEach(source -> {
            String body = readSource(source);
            assertThat(body.contains("interface ") || body.contains("extends RuntimeException"))
                    .as("%s should be an interface or a port exception", source)
                    .isTrue();
        });
    }

    @Test
    @DisplayName("the orchestrator drives the workflow without touching a framework")
    void orchestratorIsFrameworkFree() {
        // SLF4J is permitted here: the agentic loop has to be observable in production, and a
        // logging facade binds the core to no framework.
        assertLayerIsFrameworkFree("orchestrator", FRAMEWORK_PACKAGES);
    }

    @Test
    @DisplayName("the core never reaches back into an adapter")
    void coreDoesNotDependOnAdapters() {
        Stream.of("domain", "port", "orchestrator").forEach(layer ->
                importsUnder(layer).forEach((source, imports) -> assertThat(imports)
                        .as("%s must not depend on an adapter", source)
                        .noneMatch(imported ->
                                imported.startsWith("com.ai.jmeter.agent.adapter")
                                        || imported.startsWith("com.ai.jmeter.agent.config"))));
    }

    @Test
    @DisplayName("the domain never depends on the ports that serve it")
    void domainDoesNotDependOnPorts() {
        importsUnder("domain").forEach((source, imports) -> assertThat(imports)
                .as("%s must not depend on the port layer", source)
                .noneMatch(imported -> imported.startsWith("com.ai.jmeter.agent.port")));
    }

    private static String readSource(Path source) {
        try {
            return Files.readString(source, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read " + source, e);
        }
    }
}
