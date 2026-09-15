package com.ai.jmeter.agent.adapter.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.port.TrafficParsingException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("SqlLogParserAdapter")
class SqlLogParserAdapterTest {

    /** Shaped like a MySQL slow query log: bookkeeping blocks around repeated statements. */
    private static final String SLOW_QUERY_LOG = """
            # Time: 2026-03-11T08:12:04.221510Z
            # User@Host: shop[shop] @ localhost []  Id: 42
            # Query_time: 4.512301  Lock_time: 0.000132 Rows_sent: 1  Rows_examined: 918273
            SET timestamp=1773216724;
            use shopdb;
            SELECT id, email FROM customers WHERE email = 'alice@example.com';
            # Query_time: 2.100000  Lock_time: 0.000090 Rows_sent: 24 Rows_examined: 100422
            SET timestamp=1773216725;
            SELECT o.id, o.total FROM orders o WHERE o.customer_id = 8891 AND o.status = 'OPEN';
            -- a hand-written comment left by an operator
            # Query_time: 1.000000
            SELECT id, email FROM customers WHERE email = 'alice@example.com';
            UPDATE inventory SET stock = stock - 1 WHERE sku = 'SKU-77';
            """;

    private final SqlLogParserAdapter parser = new SqlLogParserAdapter(200);

    @TempDir
    Path tempDir;

    private Path writeLog(String content) throws IOException {
        Path file = tempDir.resolve("slow.log");
        Files.writeString(file, content);
        return file;
    }

    @Test
    @DisplayName("handles SQL mode")
    void supportsSqlMode() {
        assertThat(parser.supportedMode()).isEqualTo(ExecutionMode.SQL);
    }

    @Test
    @DisplayName("extracts the statements and drops the server bookkeeping around them")
    void stripsBookkeeping() throws IOException {
        String summary = parser.parse(writeLog(SLOW_QUERY_LOG));

        assertThat(summary)
                .contains("SELECT id, email FROM customers WHERE email = 'alice@example.com';")
                .contains("UPDATE inventory SET stock = stock - 1 WHERE sku = 'SKU-77';");
        assertThat(summary)
                .doesNotContain("Query_time")
                .doesNotContain("User@Host")
                .doesNotContain("SET timestamp")
                .doesNotContain("use shopdb")
                .doesNotContain("hand-written comment");
    }

    @Test
    @DisplayName("de-duplicates repeated statements, which is most of a slow log")
    void deduplicatesStatements() throws IOException {
        String summary = parser.parse(writeLog(SLOW_QUERY_LOG));

        assertThat(summary).contains("Distinct SQL statements observed (3):");
        assertThat(summary.split("FROM customers", -1)).hasSize(2);
    }

    @Test
    @DisplayName("numbers the statements so the model can name each JDBC sampler")
    void numbersStatements() throws IOException {
        String summary = parser.parse(writeLog(SLOW_QUERY_LOG));

        assertThat(summary).contains("1. SELECT").contains("2. SELECT").contains("3. UPDATE");
    }

    @Test
    @DisplayName("collapses a statement wrapped across lines onto one line")
    void normalizesMultiLineStatements() throws IOException {
        String log = """
                SELECT
                    id,
                    name
                FROM     products
                WHERE sku = 'A1';
                """;

        assertThat(parser.parse(writeLog(log)))
                .contains("SELECT id, name FROM products WHERE sku = 'A1';");
    }

    @Test
    @DisplayName("separates statements that share a line")
    void separatesStatementsOnOneLine() throws IOException {
        String log = "DELETE FROM carts WHERE id = 1; SELECT count(*) FROM carts;";

        String summary = parser.parse(writeLog(log));

        assertThat(summary)
                .contains("Distinct SQL statements observed (2):")
                .contains("1. DELETE FROM carts WHERE id = 1;")
                .contains("2. SELECT count(*) FROM carts;");
    }

    @Test
    @DisplayName("recognizes inserts alongside the other statement kinds")
    void recognizesInserts() throws IOException {
        String log = "INSERT INTO audit (action) VALUES ('login');";

        assertThat(parser.parse(writeLog(log))).contains("INSERT INTO audit");
    }

    @Test
    @DisplayName("caps how many statements reach the model")
    void capsStatementCount() throws IOException {
        String log = """
                SELECT 1 FROM a;
                SELECT 2 FROM b;
                SELECT 3 FROM c;
                """;

        String summary = new SqlLogParserAdapter(2).parse(writeLog(log));

        assertThat(summary)
                .contains("Distinct SQL statements observed (2):")
                .doesNotContain("SELECT 3");
    }

    @Test
    @DisplayName("rejects a log with nothing that looks like SQL")
    void rejectsLogWithoutSql() throws IOException {
        Path file = writeLog("""
                # Time: 2026-03-11T08:12:04Z
                # Query_time: 4.5
                """);

        assertThatThrownBy(() -> parser.parse(file))
                .isInstanceOf(TrafficParsingException.class)
                .hasMessageContaining("no recognizable SQL statements");
    }

    @Test
    @DisplayName("reports an unreadable file rather than failing obscurely")
    void rejectsUnreadableFile() {
        Path missing = tempDir.resolve("absent.log");

        assertThatThrownBy(() -> parser.parse(missing))
                .isInstanceOf(TrafficParsingException.class)
                .hasMessageContaining("Unable to read SQL log file")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("survives a log carrying bytes that are not valid UTF-8")
    void toleratesInvalidUtf8() throws IOException {
        Path file = tempDir.resolve("binary.log");
        byte[] invalidUtf8 = {(byte) 0xC3, (byte) 0x28};
        byte[] statement = "SELECT id FROM t WHERE name = '".getBytes();
        byte[] tail = "';".getBytes();

        byte[] combined = new byte[statement.length + invalidUtf8.length + tail.length];
        System.arraycopy(statement, 0, combined, 0, statement.length);
        System.arraycopy(invalidUtf8, 0, combined, statement.length, invalidUtf8.length);
        System.arraycopy(tail, 0, combined, statement.length + invalidUtf8.length, tail.length);
        Files.write(file, combined);

        assertThat(parser.parse(file)).contains("SELECT id FROM t");
    }
}
