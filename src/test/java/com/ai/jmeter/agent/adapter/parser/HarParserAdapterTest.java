package com.ai.jmeter.agent.adapter.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.port.TrafficParsingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("HarParserAdapter")
class HarParserAdapterTest {

    /**
     * A capture shaped like a real browser recording: two meaningful API calls buried among
     * stylesheets, scripts, images and fonts, with a noisy header set on the login request.
     */
    private static final String REALISTIC_HAR = """
            {
              "log": {
                "version": "1.2",
                "entries": [
                  {
                    "request": {
                      "method": "POST",
                      "url": "https://api.shop.test/v1/login",
                      "headers": [
                        {"name": "Content-Type", "value": "application/json"},
                        {"name": "User-Agent", "value": "Mozilla/5.0"},
                        {"name": "sec-ch-ua", "value": "Chromium"},
                        {"name": "Accept-Encoding", "value": "gzip"},
                        {"name": "X-Request-Id", "value": "abc-123"}
                      ],
                      "postData": {"mimeType": "application/json", "text": "username=alice&password=s3cret"}
                    },
                    "response": {"status": 200, "content": {"mimeType": "application/json"}}
                  },
                  {
                    "request": {
                      "method": "GET",
                      "url": "https://api.shop.test/v1/orders?status=open",
                      "headers": [
                        {"name": "Authorization", "value": "Bearer eyJhbGciOi"},
                        {"name": "Accept", "value": "application/json"}
                      ]
                    },
                    "response": {"status": 200, "content": {"mimeType": "application/json; charset=utf-8"}}
                  },
                  {
                    "request": {"method": "GET", "url": "https://cdn.shop.test/assets/app.css", "headers": []},
                    "response": {"status": 200, "content": {"mimeType": "text/css"}}
                  },
                  {
                    "request": {"method": "GET", "url": "https://cdn.shop.test/assets/bundle.js", "headers": []},
                    "response": {"status": 200, "content": {"mimeType": ""}}
                  },
                  {
                    "request": {"method": "GET", "url": "https://cdn.shop.test/img/logo.png", "headers": []},
                    "response": {"status": 200, "content": {"mimeType": "image/png"}}
                  },
                  {
                    "request": {"method": "GET", "url": "https://cdn.shop.test/fonts/inter", "headers": []},
                    "response": {"status": 200, "content": {"mimeType": "font/woff2"}}
                  },
                  {
                    "request": {"method": "GET", "url": "https://cdn.shop.test/styles/theme", "headers": []},
                    "response": {"status": 200, "content": {"mimeType": "text/css"}}
                  },
                  {
                    "request": {"method": "GET", "url": "", "headers": []},
                    "response": {"status": 0, "content": {"mimeType": ""}}
                  }
                ]
              }
            }
            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HarParserAdapter parser = new HarParserAdapter(objectMapper, 150, 2000);

    @TempDir
    Path tempDir;

    private Path writeHar(String json) throws IOException {
        Path file = tempDir.resolve("traffic.har");
        Files.writeString(file, json);
        return file;
    }

    @Test
    @DisplayName("handles API mode")
    void supportsApiMode() {
        assertThat(parser.supportedMode()).isEqualTo(ExecutionMode.API);
    }

    @Test
    @DisplayName("keeps the API calls and drops every static asset")
    void stripsStaticAssets() throws IOException {
        String summary = parser.parse(writeHar(REALISTIC_HAR));

        assertThat(summary)
                .contains("/v1/login")
                .contains("/v1/orders?status=open");
        assertThat(summary)
                .as("stylesheets, scripts, images and fonts are not worth load-testing")
                .doesNotContain("app.css")
                .doesNotContain("bundle.js")
                .doesNotContain("logo.png")
                .as("an extensionless URL is still static when its content type says so")
                .doesNotContain("fonts/inter")
                .doesNotContain("styles/theme");
    }

    @Test
    @DisplayName("keeps only the headers that change how a request behaves")
    void keepsSignificantHeadersOnly() throws IOException {
        String summary = parser.parse(writeHar(REALISTIC_HAR));

        assertThat(summary)
                .contains("Content-Type")
                .contains("Authorization")
                .contains("Accept")
                .as("custom X- headers frequently carry tenant or trace context")
                .contains("X-Request-Id");
        assertThat(summary)
                .doesNotContain("User-Agent")
                .doesNotContain("sec-ch-ua")
                .doesNotContain("Accept-Encoding");
    }

    @Test
    @DisplayName("carries the request body and the observed status code through")
    void keepsBodyAndStatus() throws IOException {
        String summary = parser.parse(writeHar(REALISTIC_HAR));

        assertThat(summary)
                .contains("username=alice&password=s3cret")
                .contains("\"responseStatus\":200")
                .contains("\"method\":\"POST\"");
    }

    @Test
    @DisplayName("skips entries with no URL at all")
    void skipsBlankUrls() throws IOException {
        String summary = parser.parse(writeHar(REALISTIC_HAR));

        assertThat(objectMapper.readTree(summary)).hasSize(2);
    }

    @Test
    @DisplayName("does not mistake a dotted hostname for a file extension")
    void dottedHostnameIsNotAnExtension() throws IOException {
        String har = """
                {"log": {"entries": [
                  {"request": {"method": "GET", "url": "https://api.shop.test/v1/users", "headers": []},
                   "response": {"status": 200, "content": {"mimeType": "application/json"}}}
                ]}}
                """;

        assertThat(parser.parse(writeHar(har))).contains("/v1/users");
    }

    @Test
    @DisplayName("keeps a path whose extension is not a static asset type")
    void keepsNonStaticExtensions() throws IOException {
        String har = """
                {"log": {"entries": [
                  {"request": {"method": "GET", "url": "https://api.shop.test/config.json", "headers": []},
                   "response": {"status": 200, "content": {"mimeType": "application/json"}}}
                ]}}
                """;

        assertThat(parser.parse(writeHar(har))).contains("config.json");
    }

    @Test
    @DisplayName("ignores a query string when looking for a static extension")
    void ignoresQueryStringWhenDetectingExtensions() throws IOException {
        String har = """
                {"log": {"entries": [
                  {"request": {"method": "GET", "url": "https://api.shop.test/v1/theme?file=app.css", "headers": []},
                   "response": {"status": 200, "content": {"mimeType": "application/json"}}}
                ]}}
                """;

        assertThat(parser.parse(writeHar(har))).contains("/v1/theme?file=app.css");
    }

    @Test
    @DisplayName("truncates oversized bodies rather than spending context on them")
    void truncatesLargeBodies() throws IOException {
        String longBody = "x".repeat(50);
        String har = """
                {"log": {"entries": [
                  {"request": {"method": "POST", "url": "https://api.shop.test/v1/bulk", "headers": [],
                    "postData": {"text": "%s"}},
                   "response": {"status": 200, "content": {"mimeType": "application/json"}}}
                ]}}
                """.formatted(longBody);

        String summary = new HarParserAdapter(objectMapper, 150, 10).parse(writeHar(har));

        assertThat(summary).contains("xxxxxxxxxx...[truncated]").doesNotContain(longBody);
    }

    @Test
    @DisplayName("caps how many requests reach the model")
    void capsEntryCount() throws IOException {
        String entries = IntStream.rangeClosed(1, 5)
                .mapToObj(index -> """
                        {"request": {"method": "GET", "url": "https://api.shop.test/v1/item/%d", "headers": []},
                         "response": {"status": 200, "content": {"mimeType": "application/json"}}}"""
                        .formatted(index))
                .collect(Collectors.joining(","));

        String summary = new HarParserAdapter(objectMapper, 2, 2000)
                .parse(writeHar("{\"log\": {\"entries\": [" + entries + "]}}"));

        assertThat(objectMapper.readTree(summary)).hasSize(2);
        assertThat(summary).contains("/v1/item/1").contains("/v1/item/2").doesNotContain("/v1/item/3");
    }

    @Test
    @DisplayName("rejects a file that is not a HAR archive")
    void rejectsNonHarJson() throws IOException {
        Path file = writeHar("{\"something\": \"else\"}");

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(TrafficParsingException.class)
                .hasMessageContaining("Not a valid HAR archive");
    }

    @Test
    @DisplayName("rejects a capture that is nothing but static assets")
    void rejectsCaptureWithoutApiTraffic() throws IOException {
        Path file = writeHar("""
                {"log": {"entries": [
                  {"request": {"method": "GET", "url": "https://cdn.shop.test/a.css", "headers": []},
                   "response": {"status": 200, "content": {"mimeType": "text/css"}}}
                ]}}
                """);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(TrafficParsingException.class)
                .hasMessageContaining("no replayable API traffic");
    }

    @Test
    @DisplayName("reports an unreadable file rather than failing obscurely")
    void rejectsUnreadableFile() {
        Path missing = tempDir.resolve("absent.har");

        assertThatThrownBy(() -> parser.parse(missing))
                .isInstanceOf(TrafficParsingException.class)
                .hasMessageContaining("Unable to read HAR file")
                .hasCauseInstanceOf(IOException.class);
    }
}
