package com.ai.jmeter.agent.adapter.parser;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.port.TrafficParserPort;
import com.ai.jmeter.agent.port.TrafficParsingException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Driven adapter: lifts the distinct SQL statements out of a slow-query or trace log.
 *
 * <p>Slow query logs interleave the statements with server bookkeeping — {@code # Query_time}
 * blocks, {@code SET timestamp=...}, {@code use <schema>} — and the same statement repeats
 * thousands of times. The model only needs the distinct statement shapes, so the adapter strips
 * the bookkeeping and de-duplicates while preserving the order the statements were first seen.
 */
public final class SqlLogParserAdapter implements TrafficParserPort {

    /**
     * Matches a DML statement from its leading keyword through its terminating semicolon.
     * Reluctant so that consecutive statements on one line do not merge into one match.
     */
    private static final Pattern STATEMENT = Pattern.compile(
            "\\b(SELECT|INSERT|UPDATE|DELETE)\\b.*?;",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Slow-log bookkeeping lines that carry no statement worth testing. */
    private static final Pattern BOOKKEEPING_LINE = Pattern.compile(
            "^\\s*(#.*|--.*|/\\*.*\\*/\\s*;?|SET\\s+timestamp\\s*=.*|USE\\s+\\S+\\s*;?)\\s*$",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final int maxQueries;

    public SqlLogParserAdapter(int maxQueries) {
        this.maxQueries = maxQueries;
    }

    @Override
    public ExecutionMode supportedMode() {
        return ExecutionMode.SQL;
    }

    @Override
    public String parse(Path sourceFile) {
        String cleaned = stripBookkeeping(readLog(sourceFile));

        Set<String> distinctQueries = new LinkedHashSet<>();
        Matcher matcher = STATEMENT.matcher(cleaned);
        while (matcher.find() && distinctQueries.size() < maxQueries) {
            distinctQueries.add(normalizeWhitespace(matcher.group()));
        }

        if (distinctQueries.isEmpty()) {
            throw new TrafficParsingException(
                    "SQL log contained no recognizable SQL statements: " + sourceFile);
        }

        StringBuilder summary = new StringBuilder(
                "Distinct SQL statements observed (" + distinctQueries.size() + "):\n");
        int index = 1;
        for (String query : distinctQueries) {
            summary.append(index++).append(". ").append(query).append('\n');
        }
        return summary.toString();
    }

    private String readLog(Path sourceFile) {
        try {
            // Decoded leniently rather than via Files.readString: production slow logs regularly
            // carry non-UTF-8 bytes inside bound parameters, and a decoding error must not abort
            // a run over payload bytes the test plan never reproduces.
            return new String(Files.readAllBytes(sourceFile), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new TrafficParsingException("Unable to read SQL log file: " + sourceFile, e);
        }
    }

    private String stripBookkeeping(String rawLog) {
        return rawLog.lines()
                .filter(line -> !BOOKKEEPING_LINE.matcher(line).matches())
                .collect(Collectors.joining("\n", "", "\n"));
    }

    private String normalizeWhitespace(String statement) {
        return WHITESPACE.matcher(statement).replaceAll(" ").trim();
    }
}
