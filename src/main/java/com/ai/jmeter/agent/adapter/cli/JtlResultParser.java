package com.ai.jmeter.agent.adapter.cli;

import com.ai.jmeter.agent.domain.SampleFailure;
import com.ai.jmeter.agent.port.ExecutionEngineException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads a JMeter {@code .jtl} results file and decides which samples failed.
 *
 * <p>Columns are located by header name rather than by position, because the set and order of
 * {@code .jtl} columns is configurable per JMeter installation — hardcoding indices would make
 * the agent silently misread results on someone else's setup.
 *
 * <p>A sample counts as failed when JMeter's own {@code success} flag is false, or when the
 * response code is an error. The second condition matters: a sampler with no assertion attached
 * is recorded as successful even when the server answered 500, and an agent that trusted the
 * flag alone would declare a broken plan healthy.
 */
public final class JtlResultParser {

    private static final String COLUMN_SUCCESS = "success";
    private static final String COLUMN_RESPONSE_CODE = "responsecode";
    private static final String COLUMN_RESPONSE_MESSAGE = "responsemessage";
    private static final String COLUMN_LABEL = "label";
    private static final String COLUMN_FAILURE_MESSAGE = "failuremessage";

    private static final int FIRST_ERROR_STATUS = 400;

    private final int maxRecordedFailures;

    public JtlResultParser(int maxRecordedFailures) {
        this.maxRecordedFailures = maxRecordedFailures;
    }

    /**
     * @param jtlFile the results file JMeter was asked to write
     * @return what the run recorded; empty when JMeter never produced the file
     * @throws ExecutionEngineException if the file exists but cannot be read
     */
    public JtlAnalysis parse(Path jtlFile) {
        if (!Files.exists(jtlFile)) {
            return JtlAnalysis.empty();
        }

        List<String> lines = readLines(jtlFile).stream()
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.isEmpty()) {
            return JtlAnalysis.empty();
        }

        Map<String, Integer> columns = indexColumns(lines.get(0));
        List<SampleFailure> failures = new ArrayList<>();
        int totalSamples = 0;

        for (String line : lines.subList(1, lines.size())) {
            totalSamples++;
            List<String> fields = splitCsvLine(line);
            if (!isFailure(fields, columns)) {
                continue;
            }
            if (failures.size() < maxRecordedFailures) {
                failures.add(new SampleFailure(
                        field(fields, columns, COLUMN_LABEL),
                        field(fields, columns, COLUMN_RESPONSE_CODE),
                        field(fields, columns, COLUMN_RESPONSE_MESSAGE),
                        field(fields, columns, COLUMN_FAILURE_MESSAGE)));
            }
        }
        return new JtlAnalysis(totalSamples, failures);
    }

    private List<String> readLines(Path jtlFile) {
        try {
            // Decoded leniently: response payloads echoed into the results file are not always
            // valid UTF-8, and that must not abort the analysis of an otherwise readable run.
            return new String(Files.readAllBytes(jtlFile), StandardCharsets.UTF_8).lines().toList();
        } catch (IOException e) {
            throw new ExecutionEngineException("Unable to read JMeter results file: " + jtlFile, e);
        }
    }

    private Map<String, Integer> indexColumns(String headerLine) {
        List<String> headers = splitCsvLine(headerLine);
        Map<String, Integer> columns = new HashMap<>();
        for (int i = 0; i < headers.size(); i++) {
            columns.put(headers.get(i).trim().toLowerCase(), i);
        }
        return columns;
    }

    private boolean isFailure(List<String> fields, Map<String, Integer> columns) {
        String success = field(fields, columns, COLUMN_SUCCESS);
        if (!success.isBlank() && !Boolean.parseBoolean(success)) {
            return true;
        }
        return isErrorResponseCode(field(fields, columns, COLUMN_RESPONSE_CODE));
    }

    private boolean isErrorResponseCode(String responseCode) {
        if (responseCode.isBlank()) {
            return false;
        }
        try {
            return Integer.parseInt(responseCode.trim()) >= FIRST_ERROR_STATUS;
        } catch (NumberFormatException e) {
            // JMeter writes prose here for transport-level problems, for example
            // "Non HTTP response code: java.net.ConnectException". Never a success.
            return true;
        }
    }

    private String field(List<String> fields, Map<String, Integer> columns, String column) {
        Integer index = columns.get(column);
        if (index == null || index >= fields.size()) {
            return "";
        }
        return fields.get(index);
    }

    /**
     * Splits one CSV record, honouring quoted fields and doubled-quote escapes.
     *
     * <p>Hand-rolled rather than pulled from a CSV library: JMeter emits a strict, narrow dialect,
     * and this keeps the execution adapter free of a dependency that would only be used here.
     */
    private static List<String> splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < line.length(); i++) {
            char character = line.charAt(i);
            if (inQuotes) {
                if (character != '"') {
                    current.append(character);
                } else if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    inQuotes = false;
                }
            } else if (character == '"') {
                inQuotes = true;
            } else if (character == ',') {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(character);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
