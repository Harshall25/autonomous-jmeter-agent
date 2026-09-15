package com.ai.jmeter.agent.adapter.parser;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.TrafficParsingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/**
 * Driven adapter: derives a workload from a Postman collection.
 *
 * <p>Worth supporting because a collection is usually the one artifact a QA team already curates
 * by hand: the requests are in the order a real user makes them, the auth flow is already worked
 * out, and Postman's own {@code {{variable}}} syntax marks exactly what the team considers
 * parameterizable. That last point makes a collection unusually good evidence — the human
 * correlation decisions are already encoded, and translating them to JMeter's {@code ${variable}}
 * is mechanical rather than inferential.
 *
 * <p>Items nest arbitrarily deep in folders, so traversal is recursive with a depth bound to keep
 * a malformed or self-referential export from exhausting the stack.
 */
public final class PostmanCollectionParserAdapter implements TrafficParserPort {

    private static final int MAX_FOLDER_DEPTH = 12;

    /** Headers worth keeping, matching the HAR adapter so both feed the model the same shape. */
    private static final Set<String> SIGNIFICANT_HEADERS = Set.of(
            "authorization", "content-type", "accept", "cookie", "referer", "origin");

    private final ObjectMapper objectMapper;
    private final int maxRequests;
    private final int maxBodyCharacters;

    public PostmanCollectionParserAdapter(
            ObjectMapper objectMapper, int maxRequests, int maxBodyCharacters) {
        this.objectMapper = objectMapper;
        this.maxRequests = maxRequests;
        this.maxBodyCharacters = maxBodyCharacters;
    }

    @Override
    public ExecutionMode supportedMode() {
        return ExecutionMode.POSTMAN;
    }

    @Override
    public String parse(Path sourceFile) {
        JsonNode root = read(sourceFile);

        JsonNode items = root.path("item");
        if (!items.isArray()) {
            throw new TrafficParsingException(
                    "Not a valid Postman collection, expected an item array: " + sourceFile);
        }

        ArrayNode summary = objectMapper.createArrayNode();
        collect(items, summary, 0);

        if (summary.isEmpty()) {
            throw new TrafficParsingException(
                    "Postman collection contains no requests: " + sourceFile);
        }
        return summary.toString();
    }

    private void collect(JsonNode items, ArrayNode summary, int depth) {
        if (depth > MAX_FOLDER_DEPTH) {
            return;
        }
        for (JsonNode item : items) {
            if (summary.size() >= maxRequests) {
                return;
            }
            JsonNode nested = item.path("item");
            if (nested.isArray()) {
                collect(nested, summary, depth + 1);
            } else if (item.has("request")) {
                summary.add(summarize(item));
            }
        }
    }

    private ObjectNode summarize(JsonNode item) {
        JsonNode request = item.path("request");
        ObjectNode node = objectMapper.createObjectNode();

        node.put("name", item.path("name").asText(""));
        node.put("method", request.path("method").asText("GET"));
        node.put("url", urlOf(request.path("url")));

        ObjectNode headers = node.putObject("headers");
        for (JsonNode header : request.path("header")) {
            String name = header.path("key").asText("");
            if (isSignificantHeader(name) && !header.path("disabled").asBoolean(false)) {
                headers.put(name, header.path("value").asText(""));
            }
        }

        String body = request.path("body").path("raw").asText("");
        if (!body.isBlank()) {
            node.put("requestBody", truncate(body));
        }
        return node;
    }

    /** Postman writes a URL either as a raw string or as a decomposed object. */
    private static String urlOf(JsonNode url) {
        if (url.isTextual()) {
            return url.asText();
        }
        String raw = url.path("raw").asText("");
        if (!raw.isEmpty()) {
            return raw;
        }
        StringBuilder rebuilt = new StringBuilder();
        for (JsonNode host : url.path("host")) {
            rebuilt.append(rebuilt.isEmpty() ? "" : ".").append(host.asText());
        }
        for (JsonNode segment : url.path("path")) {
            rebuilt.append('/').append(segment.asText());
        }
        return rebuilt.toString();
    }

    private boolean isSignificantHeader(String name) {
        String lower = name.toLowerCase();
        return SIGNIFICANT_HEADERS.contains(lower) || lower.startsWith("x-");
    }

    private String truncate(String body) {
        return body.length() <= maxBodyCharacters
                ? body
                : body.substring(0, maxBodyCharacters) + "...[truncated]";
    }

    private JsonNode read(Path sourceFile) {
        try {
            return objectMapper.readTree(sourceFile.toFile());
        } catch (IOException e) {
            throw new TrafficParsingException(
                    "Unable to read Postman collection: " + sourceFile, e);
        }
    }
}
