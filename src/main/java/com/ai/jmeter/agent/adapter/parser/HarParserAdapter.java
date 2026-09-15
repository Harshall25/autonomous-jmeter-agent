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
 * Driven adapter: distills an HTTP Archive capture down to the API calls worth load-testing.
 *
 * <p>A browser-recorded HAR is mostly noise — stylesheets, fonts, images, analytics beacons and
 * a dozen {@code sec-ch-*} headers per request. Handing that to a model wastes context on
 * traffic nobody load-tests and buries the handful of calls that actually matter. This adapter
 * drops static assets, keeps only the headers that influence a test plan (auth, content
 * negotiation, session cookies, custom {@code X-} headers) and emits compact JSON.
 */
public final class HarParserAdapter implements TrafficParserPort {

    /** File extensions that are never worth replaying as API load. */
    private static final Set<String> STATIC_EXTENSIONS = Set.of(
            "css", "js", "mjs", "map", "png", "jpg", "jpeg", "gif", "svg", "ico", "webp",
            "avif", "bmp", "woff", "woff2", "ttf", "otf", "eot", "mp4", "webm", "mp3", "wav");

    /** Response content types that mark a static asset regardless of the URL shape. */
    private static final Set<String> STATIC_MIME_TYPES = Set.of(
            "text/css", "application/javascript", "text/javascript",
            "application/x-javascript", "application/font-woff");

    private static final Set<String> STATIC_MIME_PREFIXES = Set.of("image/", "font/", "video/", "audio/");

    /**
     * Headers that change how a request behaves and therefore must survive into the test plan.
     * Everything else (user agent, client hints, encoding negotiation) is dropped.
     */
    private static final Set<String> SIGNIFICANT_HEADERS = Set.of(
            "authorization", "content-type", "accept", "cookie", "referer", "origin");

    /** A 101 marks the handshake where an HTTP request becomes a WebSocket connection. */
    private static final int HTTP_SWITCHING_PROTOCOLS = 101;

    private final ObjectMapper objectMapper;
    private final int maxEntries;
    private final int maxBodyCharacters;

    public HarParserAdapter(ObjectMapper objectMapper, int maxEntries, int maxBodyCharacters) {
        this.objectMapper = objectMapper;
        this.maxEntries = maxEntries;
        this.maxBodyCharacters = maxBodyCharacters;
    }

    @Override
    public ExecutionMode supportedMode() {
        return ExecutionMode.API;
    }

    @Override
    public String parse(Path sourceFile) {
        JsonNode root = readHar(sourceFile);

        JsonNode entries = root.path("log").path("entries");
        if (!entries.isArray()) {
            throw new TrafficParsingException(
                    "Not a valid HAR archive, expected log.entries array: " + sourceFile);
        }

        ArrayNode summary = objectMapper.createArrayNode();
        for (JsonNode entry : entries) {
            if (summary.size() >= maxEntries) {
                break;
            }
            JsonNode request = entry.path("request");
            String url = request.path("url").asText("");
            if (url.isBlank() || isStaticAsset(url, entry.path("response"))) {
                continue;
            }
            summary.add(summarize(request, entry.path("response"), url));
        }

        if (summary.isEmpty()) {
            throw new TrafficParsingException(
                    "HAR archive contained no replayable API traffic: " + sourceFile);
        }
        // JsonNode.toString() emits compact JSON without declaring a checked exception.
        return summary.toString();
    }

    private JsonNode readHar(Path sourceFile) {
        try {
            return objectMapper.readTree(sourceFile.toFile());
        } catch (IOException e) {
            throw new TrafficParsingException("Unable to read HAR file: " + sourceFile, e);
        }
    }

    private ObjectNode summarize(JsonNode request, JsonNode response, String url) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("method", request.path("method").asText("GET"));
        node.put("url", url);
        node.put("responseStatus", response.path("status").asInt(0));
        node.put("protocol", detectProtocol(url, request, response));

        ObjectNode headers = node.putObject("headers");
        for (JsonNode header : request.path("headers")) {
            String name = header.path("name").asText("");
            if (isSignificantHeader(name)) {
                headers.put(name, header.path("value").asText(""));
            }
        }

        String body = request.path("postData").path("text").asText("");
        if (!body.isBlank()) {
            node.put("requestBody", truncate(body));
        }
        return node;
    }

    /**
     * Labels each request with the protocol family whose sampler should replay it.
     *
     * <p>A single capture routinely mixes REST, GraphQL and WebSocket traffic, and each needs a
     * different JMeter sampler. Deciding this per request rather than per run is what lets one
     * plan exercise a modern front end honestly; a run-level protocol setting would force the
     * model to mislabel everything that did not match.
     */
    private static String detectProtocol(String url, JsonNode request, JsonNode response) {
        String lowerUrl = url.toLowerCase();
        if (lowerUrl.startsWith("ws://") || lowerUrl.startsWith("wss://")
                || response.path("status").asInt(0) == HTTP_SWITCHING_PROTOCOLS) {
            return "websocket";
        }
        if (contentTypeOf(request).startsWith("application/grpc")) {
            return "grpc";
        }
        // A GraphQL endpoint is conventionally one URL for every operation, so the URL alone is
        // not enough — the body is what distinguishes a query from a mutation.
        if (lowerUrl.contains("/graphql")
                || request.path("postData").path("text").asText("").contains("\"query\"")) {
            return "graphql";
        }
        return "http";
    }

    private static String contentTypeOf(JsonNode request) {
        for (JsonNode header : request.path("headers")) {
            if ("content-type".equalsIgnoreCase(header.path("name").asText(""))) {
                return header.path("value").asText("").toLowerCase();
            }
        }
        return "";
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

    private boolean isStaticAsset(String url, JsonNode response) {
        return hasStaticExtension(url) || hasStaticContentType(response);
    }

    private boolean hasStaticExtension(String url) {
        int queryStart = url.indexOf('?');
        String path = queryStart < 0 ? url : url.substring(0, queryStart);
        int lastDot = path.lastIndexOf('.');
        if (lastDot <= path.lastIndexOf('/')) {
            return false;
        }
        return STATIC_EXTENSIONS.contains(path.substring(lastDot + 1).toLowerCase());
    }

    private boolean hasStaticContentType(JsonNode response) {
        String mimeType = response.path("content").path("mimeType").asText("").toLowerCase();
        int parameterStart = mimeType.indexOf(';');
        if (parameterStart >= 0) {
            mimeType = mimeType.substring(0, parameterStart).trim();
        }
        if (STATIC_MIME_TYPES.contains(mimeType)) {
            return true;
        }
        for (String prefix : STATIC_MIME_PREFIXES) {
            if (mimeType.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
