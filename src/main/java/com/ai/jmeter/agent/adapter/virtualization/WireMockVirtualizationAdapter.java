package com.ai.jmeter.agent.adapter.virtualization;

import com.ai.jmeter.agent.domain.virtualization.StubbedDependency;
import com.ai.jmeter.agent.domain.virtualization.VirtualizedEnvironment;
import com.ai.jmeter.agent.port.ServiceVirtualizationPort;
import com.ai.jmeter.agent.port.VirtualizationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: writes WireMock stub mappings and a compose file to run them.
 *
 * <p>Generates configuration rather than starting a server. That keeps the agent out of the
 * business of managing a process it did not start and cannot reliably stop, and it means the same
 * stub set can be checked into a repository, reviewed, and reused by a CI pipeline that stands the
 * environment up its own way.
 */
public final class WireMockVirtualizationAdapter implements ServiceVirtualizationPort {

    private static final Logger log =
            LoggerFactory.getLogger(WireMockVirtualizationAdapter.class);

    private static final String MAPPINGS_DIRECTORY = "stubs/mappings";
    private static final String COMPOSE_FILE = "stubs/docker-compose.yml";

    private final ObjectMapper objectMapper;
    private final Path workspaceDirectory;
    private final String image;
    private final int port;

    public WireMockVirtualizationAdapter(
            ObjectMapper objectMapper, Path workspaceDirectory, String image, int port) {
        this.objectMapper = objectMapper;
        this.workspaceDirectory = workspaceDirectory;
        this.image = image;
        this.port = port;
    }

    @Override
    public VirtualizedEnvironment virtualize(List<StubbedDependency> dependencies) {
        Path mappings = workspaceDirectory.resolve(MAPPINGS_DIRECTORY);
        Path compose = workspaceDirectory.resolve(COMPOSE_FILE);

        try {
            Files.createDirectories(mappings);
            for (StubbedDependency dependency : dependencies) {
                Files.writeString(
                        mappings.resolve(fileNameFor(dependency)),
                        renderMappings(dependency),
                        StandardCharsets.UTF_8);
            }
            Files.writeString(compose, renderCompose(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new VirtualizationException(
                    "Unable to write service virtualization stubs to " + mappings, e);
        }

        VirtualizedEnvironment environment =
                new VirtualizedEnvironment(mappings, compose, dependencies, port);
        log.info("Service virtualization ready:\n{}", environment.describe());
        return environment;
    }

    /**
     * Renders one stub file.
     *
     * <p>A dependency that injects failures becomes two mappings rather than one: WireMock chooses
     * between same-priority mappings, so a failing variant alongside the healthy one produces a
     * mixed response stream. A single mapping cannot express "fails sometimes", and testing
     * against a dependency that never fails proves nothing about what happens when it does.
     */
    private String renderMappings(StubbedDependency dependency) {
        ObjectNode root = objectMapper.createObjectNode();
        var mappings = root.putArray("mappings");

        mappings.add(mapping(dependency, dependency.status(), dependency.responseBody(),
                dependency.injectsFailures() ? 100 - dependency.failurePercentage() : null));

        if (dependency.injectsFailures()) {
            mappings.add(mapping(dependency, 500,
                    "{\"error\":\"injected failure from service virtualization\"}",
                    dependency.failurePercentage()));
        }
        return root.toPrettyString();
    }

    private ObjectNode mapping(
            StubbedDependency dependency, int status, String body, Integer weight) {
        ObjectNode mapping = objectMapper.createObjectNode();

        ObjectNode request = mapping.putObject("request");
        request.put("method", dependency.method().toUpperCase(Locale.ROOT));
        request.put("urlPattern", dependency.urlPattern());

        ObjectNode response = mapping.putObject("response");
        response.put("status", status);
        response.put("body", body);
        response.putObject("headers").put("Content-Type", "application/json");
        if (dependency.fixedDelayMillis() > 0) {
            response.put("fixedDelayMilliseconds", dependency.fixedDelayMillis());
        }

        if (weight != null) {
            ObjectNode metadata = mapping.putObject("metadata");
            metadata.put("weightPercentage", weight);
        }
        return mapping;
    }

    private String renderCompose() {
        return """
                services:
                  dependency-stubs:
                    image: %s
                    command: ["--global-response-templating", "--verbose"]
                    ports:
                      - "%d:8080"
                    volumes:
                      - ./mappings:/home/wiremock/mappings:ro
                """.formatted(image, port);
    }

    private static String fileNameFor(StubbedDependency dependency) {
        return dependency.name().replaceAll("[^A-Za-z0-9._-]", "-") + ".json";
    }
}
