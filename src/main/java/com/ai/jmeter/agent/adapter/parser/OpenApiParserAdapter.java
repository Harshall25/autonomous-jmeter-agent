package com.ai.jmeter.agent.adapter.parser;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.TrafficParsingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Driven adapter: derives a workload from an OpenAPI document rather than from captured traffic.
 *
 * <p>This is what lets the agent work before anyone has recorded production traffic — most teams
 * have an API contract months before they have a HAR file, so requiring a capture first is an
 * adoption barrier rather than a technical one.
 *
 * <p>A spec is weaker evidence than a capture in one specific way: it describes what endpoints
 * exist but not the order a user hits them in, nor which response field feeds the next request.
 * The adapter compensates by emitting an explicit dependency hint — operations are ordered so that
 * anything resembling a login or token endpoint comes first, giving the model a starting point for
 * correlation it would otherwise have to guess at.
 */
public final class OpenApiParserAdapter implements TrafficParserPort {

    private static final Set<String> HTTP_METHODS =
            Set.of("get", "put", "post", "delete", "patch", "head", "options", "trace");

    /** Operations whose responses typically define the variables everything else consumes. */
    private static final List<String> AUTHENTICATION_HINTS =
            List.of("login", "token", "auth", "session", "signin", "oauth");

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final int maxOperations;

    public OpenApiParserAdapter(ObjectMapper jsonMapper, int maxOperations) {
        this.jsonMapper = jsonMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.maxOperations = maxOperations;
    }

    @Override
    public ExecutionMode supportedMode() {
        return ExecutionMode.OPENAPI;
    }

    @Override
    public String parse(Path sourceFile) {
        JsonNode root = read(sourceFile);

        JsonNode paths = root.path("paths");
        if (!paths.isObject()) {
            throw new TrafficParsingException(
                    "Not a valid OpenAPI document, expected a paths object: " + sourceFile);
        }

        String baseUrl = firstServerUrl(root);
        List<ObjectNode> operations = new java.util.ArrayList<>();

        Iterator<Map.Entry<String, JsonNode>> pathEntries = paths.fields();
        while (pathEntries.hasNext() && operations.size() < maxOperations) {
            Map.Entry<String, JsonNode> pathEntry = pathEntries.next();
            Iterator<Map.Entry<String, JsonNode>> methodEntries = pathEntry.getValue().fields();

            while (methodEntries.hasNext() && operations.size() < maxOperations) {
                Map.Entry<String, JsonNode> methodEntry = methodEntries.next();
                if (HTTP_METHODS.contains(methodEntry.getKey().toLowerCase())) {
                    operations.add(summarize(
                            baseUrl, pathEntry.getKey(),
                            methodEntry.getKey(), methodEntry.getValue()));
                }
            }
        }

        if (operations.isEmpty()) {
            throw new TrafficParsingException(
                    "OpenAPI document declares no operations: " + sourceFile);
        }

        // Authentication first: the model needs a plausible correlation starting point, which a
        // spec — unlike a capture — gives no ordering evidence for.
        operations.sort(java.util.Comparator.comparingInt(
                operation -> looksLikeAuthentication(operation.path("url").asText()) ? 0 : 1));

        ArrayNode summary = jsonMapper.createArrayNode();
        operations.forEach(summary::add);
        return summary.toString();
    }

    private ObjectNode summarize(
            String baseUrl, String path, String method, JsonNode operation) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.put("method", method.toUpperCase());
        node.put("url", baseUrl + path);
        node.put("operationId", operation.path("operationId").asText(""));
        node.put("summary", operation.path("summary").asText(""));

        ArrayNode parameters = node.putArray("parameters");
        for (JsonNode parameter : operation.path("parameters")) {
            ObjectNode described = parameters.addObject();
            described.put("name", parameter.path("name").asText(""));
            described.put("in", parameter.path("in").asText(""));
            described.put("required", parameter.path("required").asBoolean(false));
            described.put("type", parameter.path("schema").path("type").asText("string"));
        }

        String contentType = firstRequestContentType(operation);
        if (!contentType.isEmpty()) {
            node.put("requestContentType", contentType);
        }

        ArrayNode statuses = node.putArray("declaredResponses");
        operation.path("responses").fieldNames().forEachRemaining(statuses::add);
        return node;
    }

    private static String firstRequestContentType(JsonNode operation) {
        Iterator<String> contentTypes = operation.path("requestBody").path("content").fieldNames();
        return contentTypes.hasNext() ? contentTypes.next() : "";
    }

    /**
     * @return the first declared server URL, or an empty prefix so paths stay relative and the
     * operator supplies the host at run time
     */
    private static String firstServerUrl(JsonNode root) {
        JsonNode servers = root.path("servers");
        if (servers.isArray() && !servers.isEmpty()) {
            return servers.get(0).path("url").asText("");
        }
        return "";
    }

    private static boolean looksLikeAuthentication(String path) {
        String lower = path.toLowerCase();
        return AUTHENTICATION_HINTS.stream().anyMatch(lower::contains);
    }

    /** Reads JSON or YAML, since specs are published in both and operators rarely convert. */
    private JsonNode read(Path sourceFile) {
        ObjectMapper mapper = isYaml(sourceFile) ? yamlMapper : jsonMapper;
        try {
            return mapper.readTree(sourceFile.toFile());
        } catch (IOException e) {
            throw new TrafficParsingException(
                    "Unable to read OpenAPI document: " + sourceFile, e);
        }
    }

    private static boolean isYaml(Path sourceFile) {
        String filename = sourceFile.getFileName().toString().toLowerCase();
        return filename.endsWith(".yaml") || filename.endsWith(".yml");
    }
}
