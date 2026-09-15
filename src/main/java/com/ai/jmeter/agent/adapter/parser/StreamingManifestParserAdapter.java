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

/**
 * Driven adapter: reads a manifest of broker topics and the messages published to them.
 *
 * <p>Event pipelines have no equivalent of a HAR file — there is no single capture that shows a
 * consumer falling behind — so the workload has to be declared rather than observed. The manifest
 * is the smallest declaration that still says something useful: which topics exist, what shape
 * their messages are, how they are keyed, and what throughput and lag the pipeline is expected to
 * sustain.
 *
 * <p>Partition count and key field are carried through deliberately. Skew is the failure mode that
 * aggregate throughput hides completely, and the model cannot reason about it without knowing how
 * messages are keyed across how many partitions.
 */
public final class StreamingManifestParserAdapter implements TrafficParserPort {

    private final ObjectMapper jsonMapper;
    private final ObjectMapper yamlMapper;
    private final int maxTopics;

    public StreamingManifestParserAdapter(ObjectMapper jsonMapper, int maxTopics) {
        this.jsonMapper = jsonMapper;
        this.yamlMapper = new ObjectMapper(new YAMLFactory());
        this.maxTopics = maxTopics;
    }

    @Override
    public ExecutionMode supportedMode() {
        return ExecutionMode.STREAMING;
    }

    @Override
    public String parse(Path sourceFile) {
        JsonNode root = read(sourceFile);

        JsonNode topics = root.path("topics");
        if (!topics.isArray()) {
            throw new TrafficParsingException(
                    "Not a valid streaming manifest, expected a topics array: " + sourceFile);
        }

        ObjectNode summary = jsonMapper.createObjectNode();
        summary.put("broker", root.path("broker").asText("kafka"));

        ArrayNode described = summary.putArray("topics");
        for (JsonNode topic : topics) {
            if (described.size() >= maxTopics) {
                break;
            }
            String name = topic.path("name").asText("");
            if (!name.isBlank()) {
                described.add(summarize(topic, name));
            }
        }

        if (described.isEmpty()) {
            throw new TrafficParsingException(
                    "Streaming manifest declares no named topics: " + sourceFile);
        }
        return summary.toString();
    }

    private ObjectNode summarize(JsonNode topic, String name) {
        ObjectNode node = jsonMapper.createObjectNode();
        node.put("name", name);
        node.put("role", topic.path("role").asText("produce-and-consume"));
        node.put("partitions", topic.path("partitions").asInt(1));
        node.put("keyField", topic.path("keyField").asText(""));
        node.put("consumerGroup", topic.path("consumerGroup").asText(""));
        node.put("targetMessagesPerSecond", topic.path("targetMessagesPerSecond").asInt(0));
        node.put("maxAcceptableLagMessages", topic.path("maxAcceptableLagMessages").asInt(0));

        JsonNode sample = topic.path("sampleMessage");
        if (!sample.isMissingNode()) {
            // Carried as text: the model needs the shape, and re-nesting it would let a large
            // example balloon the prompt.
            node.put("sampleMessage", sample.toString());
        }
        return node;
    }

    private JsonNode read(Path sourceFile) {
        String filename = sourceFile.getFileName().toString().toLowerCase();
        ObjectMapper mapper =
                filename.endsWith(".yaml") || filename.endsWith(".yml") ? yamlMapper : jsonMapper;
        try {
            return mapper.readTree(sourceFile.toFile());
        } catch (IOException e) {
            throw new TrafficParsingException(
                    "Unable to read streaming manifest: " + sourceFile, e);
        }
    }
}
