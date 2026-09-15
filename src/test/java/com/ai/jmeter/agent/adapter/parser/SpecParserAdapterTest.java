package com.ai.jmeter.agent.adapter.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.port.TrafficParsingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Covers the design-time ingestion paths that let the agent work without a traffic capture. */
class SpecParserAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    Path tempDir;

    private Path write(String filename, String content) throws IOException {
        Path file = tempDir.resolve(filename);
        Files.writeString(file, content);
        return file;
    }

    @Nested
    @DisplayName("OpenApiParserAdapter")
    class OpenApi {

        private final OpenApiParserAdapter parser = new OpenApiParserAdapter(objectMapper, 150);

        private static final String SPEC_YAML = """
                openapi: 3.0.3
                info:
                  title: Shop API
                servers:
                  - url: https://api.shop.test
                paths:
                  /v1/orders:
                    get:
                      operationId: listOrders
                      summary: List orders for the current customer
                      parameters:
                        - name: status
                          in: query
                          required: false
                          schema:
                            type: string
                      responses:
                        '200':
                          description: ok
                        '401':
                          description: unauthorized
                  /v1/auth/login:
                    post:
                      operationId: login
                      summary: Exchange credentials for a token
                      requestBody:
                        content:
                          application/json:
                            schema:
                              type: object
                      responses:
                        '200':
                          description: ok
                """;

        @Test
        @DisplayName("handles OpenAPI mode")
        void supportsMode() {
            assertThat(parser.supportedMode()).isEqualTo(ExecutionMode.OPENAPI);
        }

        @Test
        @DisplayName("turns each operation into a request the model can plan around")
        void extractsOperations() throws IOException {
            String summary = parser.parse(write("openapi.yaml", SPEC_YAML));

            assertThat(summary)
                    .contains("https://api.shop.test/v1/orders")
                    .contains("\"method\":\"GET\"")
                    .contains("listOrders")
                    .contains("List orders for the current customer");
        }

        @Test
        @DisplayName("carries parameters through so they can be parameterized")
        void extractsParameters() throws IOException {
            String summary = parser.parse(write("openapi.yaml", SPEC_YAML));

            assertThat(summary)
                    .contains("\"name\":\"status\"")
                    .contains("\"in\":\"query\"")
                    .contains("\"required\":false");
        }

        @Test
        @DisplayName("records the declared status codes, including the ones that mean failure")
        void extractsDeclaredResponses() throws IOException {
            String summary = parser.parse(write("openapi.yaml", SPEC_YAML));

            assertThat(summary).contains("\"declaredResponses\":[\"200\",\"401\"]");
        }

        @Test
        @DisplayName("records the request content type")
        void extractsRequestContentType() throws IOException {
            assertThat(parser.parse(write("openapi.yaml", SPEC_YAML)))
                    .contains("\"requestContentType\":\"application/json\"");
        }

        @Test
        @DisplayName("puts authentication first, since a spec gives no ordering evidence")
        void ordersAuthenticationFirst() throws IOException {
            // Unlike a capture, a spec does not say which call comes first. Leading with login
            // gives the model a plausible correlation starting point instead of a guess.
            String summary = parser.parse(write("openapi.yaml", SPEC_YAML));

            assertThat(summary.indexOf("/v1/auth/login"))
                    .isLessThan(summary.indexOf("/v1/orders"));
        }

        @Test
        @DisplayName("reads a JSON specification as readily as a YAML one")
        void readsJsonSpecifications() throws IOException {
            String json = """
                    {"openapi":"3.0.3","paths":{"/v1/items":{"get":{"operationId":"listItems",
                    "responses":{"200":{"description":"ok"}}}}}}""";

            assertThat(parser.parse(write("openapi.json", json))).contains("listItems");
        }

        @Test
        @DisplayName("leaves paths relative when the spec declares no server")
        void toleratesMissingServers() throws IOException {
            String json = """
                    {"paths":{"/v1/items":{"get":{"responses":{"200":{"description":"ok"}}}}}}""";

            assertThat(parser.parse(write("openapi.json", json))).contains("\"url\":\"/v1/items\"");
        }

        @Test
        @DisplayName("ignores path-level keys that are not HTTP methods")
        void ignoresNonMethodKeys() throws IOException {
            String json = """
                    {"paths":{"/v1/items":{"summary":"not a method","parameters":[],
                    "get":{"responses":{"200":{"description":"ok"}}}}}}""";

            assertThat(objectMapper.readTree(parser.parse(write("openapi.json", json))))
                    .hasSize(1);
        }

        @Test
        @DisplayName("caps how many operations reach the model")
        void capsOperations() throws IOException {
            String json = """
                    {"paths":{"/a":{"get":{"responses":{}}},"/b":{"get":{"responses":{}}},
                    "/c":{"get":{"responses":{}}}}}""";

            String summary = new OpenApiParserAdapter(objectMapper, 2)
                    .parse(write("openapi.json", json));

            assertThat(objectMapper.readTree(summary)).hasSize(2);
        }

        @Test
        @DisplayName("stops mid-path once the operation cap is reached")
        void capsOperationsWithinOnePath() throws IOException {
            String json = """
                    {"paths":{"/v1/items":{"get":{"responses":{}},"post":{"responses":{}},
                    "delete":{"responses":{}}}}}""";

            String summary = new OpenApiParserAdapter(objectMapper, 2)
                    .parse(write("openapi.json", json));

            assertThat(objectMapper.readTree(summary)).hasSize(2);
        }

        @Test
        @DisplayName("leaves paths relative when the servers list is present but empty")
        void toleratesEmptyServersList() throws IOException {
            String json = """
                    {"servers":[],"paths":{"/v1/items":{"get":{"responses":{}}}}}""";

            assertThat(parser.parse(write("openapi.json", json)))
                    .contains("\"url\":\"/v1/items\"");
        }

        @Test
        @DisplayName("reads a .yml specification as readily as a .yaml one")
        void readsShortYamlExtension() throws IOException {
            String yaml = """
                    paths:
                      /v1/items:
                        get:
                          operationId: listItems
                          responses:
                            '200':
                              description: ok
                    """;

            assertThat(parser.parse(write("openapi.yml", yaml))).contains("listItems");
        }

        @Test
        @DisplayName("rejects a document that is not an OpenAPI specification")
        void rejectsNonSpecification() throws IOException {
            Path file = write("openapi.json", "{\"something\":\"else\"}");

            assertThatThrownBy(() -> parser.parse(file))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("expected a paths object");
        }

        @Test
        @DisplayName("rejects a specification that declares no operations")
        void rejectsEmptySpecification() throws IOException {
            Path file = write("openapi.json", "{\"paths\":{}}");

            assertThatThrownBy(() -> parser.parse(file))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("declares no operations");
        }

        @Test
        @DisplayName("reports an unreadable specification")
        void rejectsUnreadableFile() {
            Path missing = tempDir.resolve("absent.yaml");

            assertThatThrownBy(() -> parser.parse(missing))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("Unable to read OpenAPI document")
                    .hasCauseInstanceOf(IOException.class);
        }
    }

    @Nested
    @DisplayName("PostmanCollectionParserAdapter")
    class Postman {

        private final PostmanCollectionParserAdapter parser =
                new PostmanCollectionParserAdapter(objectMapper, 150, 2000);

        private static final String COLLECTION = """
                {
                  "info": {"name": "Shop"},
                  "item": [
                    {
                      "name": "Login",
                      "request": {
                        "method": "POST",
                        "url": {"raw": "https://api.shop.test/v1/login"},
                        "header": [
                          {"key": "Content-Type", "value": "application/json"},
                          {"key": "User-Agent", "value": "PostmanRuntime"},
                          {"key": "X-Tenant", "value": "acme"},
                          {"key": "Accept", "value": "*/*", "disabled": true}
                        ],
                        "body": {"mode": "raw", "raw": "username={{username}}"}
                      }
                    },
                    {
                      "name": "Orders folder",
                      "item": [
                        {
                          "name": "List orders",
                          "request": {
                            "method": "GET",
                            "url": {"raw": "https://api.shop.test/v1/orders"},
                            "header": [{"key": "Authorization", "value": "Bearer {{token}}"}]
                          }
                        }
                      ]
                    }
                  ]
                }
                """;

        @Test
        @DisplayName("handles Postman mode")
        void supportsMode() {
            assertThat(parser.supportedMode()).isEqualTo(ExecutionMode.POSTMAN);
        }

        @Test
        @DisplayName("walks nested folders to find every request")
        void walksNestedFolders() throws IOException {
            String summary = parser.parse(write("collection.json", COLLECTION));

            assertThat(summary).contains("/v1/login").contains("/v1/orders");
            assertThat(objectMapper.readTree(summary)).hasSize(2);
        }

        @Test
        @DisplayName("keeps the team's own variable markers, which encode their correlation work")
        void keepsPostmanVariables() throws IOException {
            // A collection is unusually good evidence: the humans already decided what is
            // parameterizable, so translating {{var}} to ${var} is mechanical, not inferential.
            String summary = parser.parse(write("collection.json", COLLECTION));

            assertThat(summary).contains("{{username}}").contains("{{token}}");
        }

        @Test
        @DisplayName("keeps only the headers that change how a request behaves")
        void keepsSignificantHeadersOnly() throws IOException {
            String summary = parser.parse(write("collection.json", COLLECTION));

            assertThat(summary)
                    .contains("Content-Type")
                    .contains("Authorization")
                    .contains("X-Tenant")
                    .doesNotContain("User-Agent")
                    .as("a header the author switched off is not part of the request")
                    .doesNotContain("*/*");
        }

        @Test
        @DisplayName("keeps the request name, which is what the sampler gets called")
        void keepsRequestNames() throws IOException {
            assertThat(parser.parse(write("collection.json", COLLECTION)))
                    .contains("\"name\":\"Login\"")
                    .contains("\"name\":\"List orders\"");
        }

        @Test
        @DisplayName("reads a URL given as a plain string")
        void readsStringUrl() throws IOException {
            String collection = """
                    {"item":[{"name":"x","request":{"method":"GET",
                    "url":"https://api.shop.test/v1/plain"}}]}""";

            assertThat(parser.parse(write("collection.json", collection)))
                    .contains("https://api.shop.test/v1/plain");
        }

        @Test
        @DisplayName("rebuilds a URL Postman stored only in decomposed form")
        void rebuildsDecomposedUrl() throws IOException {
            String collection = """
                    {"item":[{"name":"x","request":{"method":"GET",
                    "url":{"host":["api","shop","test"],"path":["v1","orders"]}}}]}""";

            assertThat(parser.parse(write("collection.json", collection)))
                    .contains("api.shop.test/v1/orders");
        }

        @Test
        @DisplayName("truncates an oversized body")
        void truncatesLargeBodies() throws IOException {
            String collection = """
                    {"item":[{"name":"x","request":{"method":"POST","url":"https://a.test/b",
                    "body":{"raw":"%s"}}}]}""".formatted("y".repeat(50));

            assertThat(new PostmanCollectionParserAdapter(objectMapper, 150, 10)
                    .parse(write("collection.json", collection)))
                    .contains("yyyyyyyyyy...[truncated]");
        }

        @Test
        @DisplayName("caps how many requests reach the model")
        void capsRequests() throws IOException {
            String collection = """
                    {"item":[
                      {"name":"a","request":{"method":"GET","url":"https://a.test/1"}},
                      {"name":"b","request":{"method":"GET","url":"https://a.test/2"}},
                      {"name":"c","request":{"method":"GET","url":"https://a.test/3"}}]}""";

            String summary = new PostmanCollectionParserAdapter(objectMapper, 2, 2000)
                    .parse(write("collection.json", collection));

            assertThat(objectMapper.readTree(summary)).hasSize(2);
        }

        @Test
        @DisplayName("stops descending a pathologically nested export")
        void boundsFolderDepth() throws IOException {
            StringBuilder nested = new StringBuilder(
                    "{\"item\":[{\"name\":\"deep\",\"request\":"
                            + "{\"method\":\"GET\",\"url\":\"https://a.test/deep\"}}]}");
            for (int depth = 0; depth < 20; depth++) {
                nested.insert(0, "{\"item\":[").append("]}");
            }
            Path file = write("collection.json", nested.toString());

            assertThatThrownBy(() -> parser.parse(file))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("contains no requests");
        }

        @Test
        @DisplayName("skips an item that is neither a folder nor a request")
        void skipsItemsWithoutRequests() throws IOException {
            String collection = """
                    {"item":[
                      {"name":"just a description"},
                      {"name":"real","request":{"method":"GET","url":"https://a.test/1"}}]}""";

            assertThat(objectMapper.readTree(parser.parse(write("collection.json", collection))))
                    .hasSize(1);
        }

        @Test
        @DisplayName("rejects a file that is not a Postman collection")
        void rejectsNonCollection() throws IOException {
            Path file = write("collection.json", "{\"something\":\"else\"}");

            assertThatThrownBy(() -> parser.parse(file))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("expected an item array");
        }

        @Test
        @DisplayName("rejects a collection with no requests in it")
        void rejectsEmptyCollection() throws IOException {
            Path file = write("collection.json", "{\"item\":[]}");

            assertThatThrownBy(() -> parser.parse(file))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("contains no requests");
        }

        @Test
        @DisplayName("reports an unreadable collection")
        void rejectsUnreadableFile() {
            Path missing = tempDir.resolve("absent.json");

            assertThatThrownBy(() -> parser.parse(missing))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("Unable to read Postman collection");
        }
    }

    @Nested
    @DisplayName("StreamingManifestParserAdapter")
    class Streaming {

        private final StreamingManifestParserAdapter parser =
                new StreamingManifestParserAdapter(objectMapper, 50);

        private static final String MANIFEST = """
                broker: kafka
                topics:
                  - name: orders.created
                    role: produce
                    partitions: 12
                    keyField: customerId
                    targetMessagesPerSecond: 500
                    maxAcceptableLagMessages: 1000
                    sampleMessage:
                      orderId: "o-1"
                      customerId: "c-9"
                  - name: orders.fulfilled
                    role: consume
                    consumerGroup: fulfilment
                """;

        @Test
        @DisplayName("handles streaming mode")
        void supportsMode() {
            assertThat(parser.supportedMode()).isEqualTo(ExecutionMode.STREAMING);
        }

        @Test
        @DisplayName("describes each topic and the role the plan plays against it")
        void describesTopics() throws IOException {
            String summary = parser.parse(write("topics.yaml", MANIFEST));

            assertThat(summary)
                    .contains("orders.created")
                    .contains("\"role\":\"produce\"")
                    .contains("orders.fulfilled")
                    .contains("\"role\":\"consume\"")
                    .contains("\"broker\":\"kafka\"");
        }

        @Test
        @DisplayName("carries partitioning and keying through, since skew hides in aggregates")
        void carriesPartitioning() throws IOException {
            String summary = parser.parse(write("topics.yaml", MANIFEST));

            assertThat(summary)
                    .contains("\"partitions\":12")
                    .contains("\"keyField\":\"customerId\"");
        }

        @Test
        @DisplayName("carries the throughput and lag targets the plan must assert on")
        void carriesTargets() throws IOException {
            String summary = parser.parse(write("topics.yaml", MANIFEST));

            assertThat(summary)
                    .contains("\"targetMessagesPerSecond\":500")
                    .contains("\"maxAcceptableLagMessages\":1000");
        }

        @Test
        @DisplayName("carries a sample message so the model knows the payload shape")
        void carriesSampleMessage() throws IOException {
            assertThat(parser.parse(write("topics.yaml", MANIFEST))).contains("orderId");
        }

        @Test
        @DisplayName("defaults the role when the manifest does not state one")
        void defaultsRole() throws IOException {
            String json = "{\"topics\":[{\"name\":\"t\"}]}";

            assertThat(parser.parse(write("topics.json", json)))
                    .contains("\"role\":\"produce-and-consume\"");
        }

        @Test
        @DisplayName("skips a topic with no name")
        void skipsUnnamedTopics() throws IOException {
            String json = "{\"topics\":[{\"partitions\":3},{\"name\":\"real\"}]}";

            assertThat(parser.parse(write("topics.json", json)))
                    .contains("real")
                    .doesNotContain("\"partitions\":3");
        }

        @Test
        @DisplayName("caps how many topics reach the model")
        void capsTopics() throws IOException {
            String json = "{\"topics\":[{\"name\":\"a\"},{\"name\":\"b\"},{\"name\":\"c\"}]}";

            String summary = new StreamingManifestParserAdapter(objectMapper, 2)
                    .parse(write("topics.json", json));

            assertThat(objectMapper.readTree(summary).path("topics")).hasSize(2);
        }

        @Test
        @DisplayName("rejects a file that is not a topic manifest")
        void rejectsNonManifest() throws IOException {
            Path file = write("topics.json", "{\"something\":\"else\"}");

            assertThatThrownBy(() -> parser.parse(file))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("expected a topics array");
        }

        @Test
        @DisplayName("rejects a manifest whose topics are all unnamed")
        void rejectsUnnamedManifest() throws IOException {
            Path file = write("topics.json", "{\"topics\":[{\"partitions\":3}]}");

            assertThatThrownBy(() -> parser.parse(file))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("declares no named topics");
        }

        @Test
        @DisplayName("reports an unreadable manifest")
        void rejectsUnreadableFile() {
            Path missing = tempDir.resolve("absent.yml");

            assertThatThrownBy(() -> parser.parse(missing))
                    .isInstanceOf(TrafficParsingException.class)
                    .hasMessageContaining("Unable to read streaming manifest");
        }
    }
}
