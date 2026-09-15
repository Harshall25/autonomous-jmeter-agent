package com.ai.jmeter.agent.adapter.workload;

import com.ai.jmeter.agent.domain.workload.WorkloadModel;
import com.ai.jmeter.agent.port.TrafficParsingException;
import com.ai.jmeter.agent.port.WorkloadProfilerPort;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: infers a workload shape from a Combined Log Format access log.
 *
 * <p>Concurrency is derived with Little's Law rather than guessed: the number of users in the
 * system equals arrival rate times the time each spends there. Taking the peak arrival rate and
 * the mean service time gives the thread count needed to reproduce that peak — a figure operators
 * otherwise pick by doubling until something breaks.
 *
 * <p>Peak rather than mean throughput, deliberately. Capacity is sized against the busiest second
 * a system has to survive, and averaging a daily log flattens exactly the spike that matters.
 */
public final class AccessLogWorkloadProfiler implements WorkloadProfilerPort {

    private static final Logger log = LoggerFactory.getLogger(AccessLogWorkloadProfiler.class);

    /**
     * Combined Log Format: host, identity, user, [timestamp], "METHOD path proto", status, bytes.
     * The trailing referer and user-agent fields are ignored.
     */
    private static final Pattern COMBINED_LOG_LINE = Pattern.compile(
            "^\\S+ \\S+ \\S+ \\[([^]]+)] \"(\\S+) (\\S+?)(?:\\?\\S*)? [^\"]*\" (\\d{3}) (\\S+)"
                    + "(?: .*)?$");

    private static final DateTimeFormatter CLF_TIMESTAMP =
            DateTimeFormatter.ofPattern("dd/MMM/yyyy:HH:mm:ss Z", Locale.ENGLISH);

    /** Requests with an identical timestamp to the previous one from any user. */
    private static final long DEFAULT_THINK_TIME_MILLIS = 1_000;

    /** A sane ceiling: a plan asking for more threads than this will die on the load generator. */
    private static final int MAX_INFERRED_USERS = 2_000;

    private final int rampUpSeconds;

    public AccessLogWorkloadProfiler(int rampUpSeconds) {
        this.rampUpSeconds = rampUpSeconds;
    }

    @Override
    public WorkloadModel profile(Path telemetryFile) {
        List<LogEntry> entries = readEntries(telemetryFile);

        if (entries.size() < WorkloadModel.minimumCredibleRequests()) {
            // A handful of lines is one person clicking around. Inferring a workload from it would
            // dress a guess up as a measurement.
            log.warn("Access log {} holds only {} usable request(s); falling back to a smoke test",
                    telemetryFile, entries.size());
            return WorkloadModel.smokeTest();
        }

        Map<Long, Long> requestsPerSecond = new LinkedHashMap<>();
        Map<String, Long> requestsPerEndpoint = new LinkedHashMap<>();
        for (LogEntry entry : entries) {
            requestsPerSecond.merge(entry.epochSecond(), 1L, Long::sum);
            requestsPerEndpoint.merge(entry.endpoint(), 1L, Long::sum);
        }

        double peakRatePerSecond = requestsPerSecond.values().stream()
                .mapToLong(Long::longValue)
                .max()
                .orElse(1);

        long windowSeconds = Math.max(1, requestsPerSecond.size());
        long thinkTimeMillis = inferThinkTime(entries);

        // Little's Law: concurrency = arrival rate x residence time. Think time is the residence
        // time a synthetic user spends between requests.
        int concurrentUsers = (int) Math.min(MAX_INFERRED_USERS,
                Math.max(1, Math.ceil(peakRatePerSecond * (thinkTimeMillis / 1000.0))));

        long totalRequests = entries.size();
        Map<String, Double> endpointMix = new LinkedHashMap<>();
        requestsPerEndpoint.forEach(
                (endpoint, count) -> endpointMix.put(endpoint, (double) count / totalRequests));

        WorkloadModel model = new WorkloadModel(
                peakRatePerSecond, concurrentUsers, rampUpSeconds,
                thinkTimeMillis, endpointMix, windowSeconds);
        log.info("Inferred workload from {} request(s):\n{}", totalRequests, model.describe());
        return model;
    }

    /**
     * @return the mean gap between consecutive requests, which stands in for user think time.
     * Falls back to one second when every request shares a timestamp, as happens in a log whose
     * resolution is coarser than its traffic.
     */
    private static long inferThinkTime(List<LogEntry> entries) {
        long totalGapMillis = 0;
        long gapCount = 0;
        for (int i = 1; i < entries.size(); i++) {
            long gap = entries.get(i).epochSecond() - entries.get(i - 1).epochSecond();
            if (gap > 0) {
                totalGapMillis += gap * 1000;
                gapCount++;
            }
        }
        return gapCount == 0 ? DEFAULT_THINK_TIME_MILLIS : totalGapMillis / gapCount;
    }

    private List<LogEntry> readEntries(Path telemetryFile) {
        List<LogEntry> entries = new ArrayList<>();
        for (String line : readLines(telemetryFile)) {
            Matcher matcher = COMBINED_LOG_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            Instant timestamp = parseTimestamp(matcher.group(1));
            if (timestamp != null) {
                entries.add(new LogEntry(
                        timestamp.getEpochSecond(),
                        matcher.group(2) + " " + matcher.group(3)));
            }
        }
        entries.sort(java.util.Comparator.comparingLong(LogEntry::epochSecond));
        return entries;
    }

    private static Instant parseTimestamp(String raw) {
        try {
            return OffsetDateTime.parse(raw, CLF_TIMESTAMP).toInstant();
        } catch (DateTimeParseException e) {
            // One unparseable line should not discard an otherwise usable log.
            return null;
        }
    }

    private List<String> readLines(Path telemetryFile) {
        try {
            return new String(Files.readAllBytes(telemetryFile), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        } catch (IOException e) {
            throw new TrafficParsingException("Unable to read access log: " + telemetryFile, e);
        }
    }

    /** @param endpoint method and path, with the query string dropped so variants collapse. */
    private record LogEntry(long epochSecond, String endpoint) {
    }
}
