package com.ai.jmeter.agent.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.SampleFailure;
import com.ai.jmeter.agent.port.ExecutionEngineException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("JtlResultParser")
class JtlResultParserTest {

    private static final String HEADER =
            "timeStamp,elapsed,label,responseCode,responseMessage,threadName,dataType,"
                    + "success,failureMessage,bytes";

    private final JtlResultParser parser = new JtlResultParser(500);

    @TempDir
    Path tempDir;

    private Path writeJtl(String content) throws IOException {
        Path file = tempDir.resolve("results.jtl");
        Files.writeString(file, content);
        return file;
    }

    @Test
    @DisplayName("treats a missing results file as a run that recorded nothing")
    void missingFileIsEmpty() {
        assertThat(parser.parse(tempDir.resolve("never-written.jtl")))
                .isEqualTo(JtlAnalysis.empty());
    }

    @Test
    @DisplayName("treats an empty results file as a run that recorded nothing")
    void emptyFileIsEmpty() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl("\n   \n"));

        assertThat(analysis.totalSamples()).isZero();
        assertThat(analysis.failures()).isEmpty();
    }

    @Test
    @DisplayName("treats a header-only file as a run that exercised nothing")
    void headerOnly() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + "\n"));

        assertThat(analysis.totalSamples()).isZero();
        assertThat(analysis.failures()).isEmpty();
    }

    @Test
    @DisplayName("passes a run where every sampler succeeded")
    void allSuccessful() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1773216724,120,login,200,OK,Thread 1-1,text,true,,512
                1773216844,88,orders,200,OK,Thread 1-1,text,true,,2048
                """));

        assertThat(analysis.totalSamples()).isEqualTo(2);
        assertThat(analysis.failures()).isEmpty();
    }

    @Test
    @DisplayName("records the details of a sampler JMeter flagged as failed")
    void recordsFlaggedFailure() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1773216724,120,login,401,Unauthorized,Thread 1-1,text,false,Token was empty,512
                """));

        assertThat(analysis.totalSamples()).isEqualTo(1);
        assertThat(analysis.failures()).containsExactly(
                new SampleFailure("login", "401", "Unauthorized", "Token was empty"));
    }

    @Test
    @DisplayName("catches a server error that JMeter recorded as successful")
    void catchesUnassertedServerError() throws IOException {
        // A sampler with no assertion attached is flagged successful whatever the server said.
        // Trusting the flag alone would let the agent declare a broken plan healthy.
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1773216724,120,checkout,500,Internal Server Error,Thread 1-1,text,true,,512
                """));

        assertThat(analysis.failures())
                .extracting(SampleFailure::responseCode)
                .containsExactly("500");
    }

    @Test
    @DisplayName("treats a transport-level failure with no numeric code as a failure")
    void catchesNonNumericResponseCode() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1773216724,0,login,"Non HTTP response code: java.net.ConnectException",\
                "Connection refused",Thread 1-1,text,true,,0
                """));

        assertThat(analysis.failures())
                .extracting(SampleFailure::responseCode)
                .containsExactly("Non HTTP response code: java.net.ConnectException");
    }

    @Test
    @DisplayName("accepts a successful sampler that recorded no response code at all")
    void acceptsBlankResponseCode() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1773216724,12,jdbc-select,,,Thread 1-1,text,true,,0
                """));

        assertThat(analysis.totalSamples()).isEqualTo(1);
        assertThat(analysis.failures()).isEmpty();
    }

    @Test
    @DisplayName("reads quoted fields containing commas without losing alignment")
    void handlesQuotedCommas() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1773216724,120,"checkout, step 2",403,Forbidden,Thread 1-1,text,false,"Expected 200, got 403",512
                """));

        assertThat(analysis.failures()).containsExactly(
                new SampleFailure("checkout, step 2", "403", "Forbidden", "Expected 200, got 403"));
    }

    @Test
    @DisplayName("unescapes doubled quotes inside a quoted field")
    void handlesEscapedQuotes() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1773216724,120,search,400,Bad Request,Thread 1-1,text,false,"field ""q"" missing",512
                """));

        assertThat(analysis.failures())
                .extracting(SampleFailure::failureMessage)
                .containsExactly("field \"q\" missing");
    }

    @Test
    @DisplayName("closes a quoted field that runs to the end of the line")
    void handlesQuotedFieldAtEndOfLine() throws IOException {
        // The last column is routinely failureMessage, so the closing quote is the final
        // character on the row with no delimiter after it to close the field.
        JtlAnalysis analysis = parser.parse(writeJtl("""
                timeStamp,label,responseCode,success,failureMessage
                1773216724,login,401,false,"Expected 200, got 401"
                """));

        assertThat(analysis.failures())
                .extracting(SampleFailure::failureMessage)
                .containsExactly("Expected 200, got 401");
    }

    @Test
    @DisplayName("tolerates a truncated row rather than failing the whole analysis")
    void toleratesShortRows() throws IOException {
        // JMeter leaves a partial final row behind when it is killed mid-write.
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1773216724,120,login,500
                """));

        assertThat(analysis.failures()).containsExactly(
                new SampleFailure("login", "500", "", ""));
    }

    @Test
    @DisplayName("falls back to the response code when the success column is absent")
    void worksWithoutSuccessColumn() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl("""
                timeStamp,label,responseCode
                1773216724,login,200
                1773216725,orders,503
                """));

        assertThat(analysis.totalSamples()).isEqualTo(2);
        assertThat(analysis.failures())
                .extracting(SampleFailure::label)
                .containsExactly("orders");
    }

    @Test
    @DisplayName("locates columns by name, not by position")
    void locatesColumnsByName() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl("""
                success,failureMessage,responseMessage,responseCode,label
                false,assertion failed,Bad Gateway,502,orders
                """));

        assertThat(analysis.failures()).containsExactly(
                new SampleFailure("orders", "502", "Bad Gateway", "assertion failed"));
    }

    @Test
    @DisplayName("caps retained failures while still counting every sample")
    void capsRecordedFailures() throws IOException {
        StringBuilder jtl = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < 10; i++) {
            jtl.append("1773216724,1,s").append(i).append(",500,Error,T,text,false,,0\n");
        }

        JtlAnalysis analysis = new JtlResultParser(3).parse(writeJtl(jtl.toString()));

        assertThat(analysis.totalSamples()).isEqualTo(10);
        assertThat(analysis.failures()).hasSize(3);
    }

    @Test
    @DisplayName("treats a missing elapsed column as zero rather than failing the analysis")
    void toleratesMissingElapsedColumn() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl("""
                label,responseCode,success
                login,200,true
                """));

        assertThat(analysis.statisticsByLabel().get("login").p95Millis()).isZero();
    }

    @Test
    @DisplayName("treats an unparseable elapsed value as zero")
    void toleratesNonNumericElapsed() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl("""
                elapsed,label,responseCode,success
                not-a-number,login,200,true
                """));

        assertThat(analysis.statisticsByLabel().get("login").maxMillis()).isZero();
    }

    @Test
    @DisplayName("summarizes latency per sampler, which is what history compares")
    void summarizesPerSampler() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1,10,login,200,OK,T,text,true,
                1,30,login,200,OK,T,text,true,
                1,500,orders,200,OK,T,text,true,
                """));

        assertThat(analysis.statisticsByLabel()).containsOnlyKeys("login", "orders");
        assertThat(analysis.statisticsByLabel().get("login").count()).isEqualTo(2);
        assertThat(analysis.statisticsByLabel().get("orders").p95Millis()).isEqualTo(500);
    }

    @Test
    @DisplayName("attributes failures to the sampler that produced them")
    void attributesFailuresPerSampler() throws IOException {
        JtlAnalysis analysis = parser.parse(writeJtl(HEADER + """

                1,10,login,200,OK,T,text,true,
                1,12,orders,401,Unauthorized,T,text,false,no token
                """));

        assertThat(analysis.statisticsByLabel().get("login").failures()).isZero();
        assertThat(analysis.statisticsByLabel().get("orders").failures()).isEqualTo(1);
    }

    @Test
    @DisplayName("reports a results file that exists but cannot be read")
    void reportsUnreadableFile() throws IOException {
        Path directoryNamedLikeResults = tempDir.resolve("results.jtl");
        Files.createDirectory(directoryNamedLikeResults);

        assertThatThrownBy(() -> parser.parse(directoryNamedLikeResults))
                .isInstanceOf(ExecutionEngineException.class)
                .hasMessageContaining("Unable to read JMeter results file");
    }

    @Test
    @DisplayName("exposes an empty analysis as a reusable value")
    void emptyAnalysis() {
        assertThat(JtlAnalysis.empty().totalSamples()).isZero();
        assertThat(JtlAnalysis.empty().failures()).isEmpty();
    }
}
