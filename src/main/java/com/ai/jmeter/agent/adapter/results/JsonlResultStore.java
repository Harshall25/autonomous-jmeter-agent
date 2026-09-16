package com.ai.jmeter.agent.adapter.results;

import com.ai.jmeter.agent.domain.results.RunSummary;
import com.ai.jmeter.agent.domain.results.SampleStatistics;
import com.ai.jmeter.agent.port.ResultStoreException;
import com.ai.jmeter.agent.port.ResultStorePort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: append-only run history on the local filesystem.
 *
 * <p>Stores summaries rather than raw samples, which is what keeps the file small enough to be
 * useful without a database: a run of a million samples reduces to a few hundred bytes, and every
 * question the analytics layer asks — is this slower than last week, where is the knee — is
 * answerable from summaries alone.
 *
 * <p>This is the floor, not the ceiling. A team running thousands of pipelines wants ClickHouse or
 * Parquet behind {@link ResultStorePort}; this implementation exists so that history works on a
 * laptop with no infrastructure at all, and so the analytics above it can be developed and tested
 * without one.
 */
public final class JsonlResultStore implements ResultStorePort {

    private static final Logger log = LoggerFactory.getLogger(JsonlResultStore.class);

    private final ObjectMapper objectMapper;
    private final Path storeFile;

    public JsonlResultStore(ObjectMapper objectMapper, Path storeFile) {
        this.objectMapper = objectMapper;
        this.storeFile = storeFile;
    }

    @Override
    public void record(RunSummary summary) {
        try {
            Files.createDirectories(storeFile.toAbsolutePath().getParent());
            String line = objectMapper.writeValueAsString(StoredRun.from(summary));
            Files.writeString(storeFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Recorded run {} ({} sample(s)) in the result history",
                    summary.runId(), summary.totalSamples());
        } catch (IOException e) {
            throw new ResultStoreException("Unable to record run results in " + storeFile, e);
        }
    }

    @Override
    public List<RunSummary> history(String planFingerprint, int limit) {
        return readAll().stream()
                .filter(run -> planFingerprint.equals(run.planFingerprint()))
                .map(StoredRun::toDomain)
                .sorted(Comparator.comparing(RunSummary::recordedAt).reversed())
                .limit(limit)
                .toList();
    }

    private List<StoredRun> readAll() {
        if (!Files.exists(storeFile)) {
            return List.of();
        }
        List<StoredRun> runs = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(storeFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    runs.add(objectMapper.readValue(line, new TypeReference<>() {
                    }));
                }
            }
        } catch (IOException e) {
            // A truncated store degrades to whatever parsed cleanly, so one bad append cannot
            // destroy a history that took weeks to accumulate.
            log.warn("Result history at {} is unreadable past entry {}", storeFile, runs.size(), e);
        }
        return runs;
    }

    /** The on-disk shape, kept separate so Jackson never touches a domain type. */
    record StoredRun(
            String runId,
            String planFingerprint,
            String recordedAt,
            List<StoredStatistics> statistics,
            int concurrentUsers,
            double throughputPerSecond) {

        static StoredRun from(RunSummary summary) {
            return new StoredRun(
                    summary.runId(),
                    summary.planFingerprint(),
                    summary.recordedAt().toString(),
                    summary.statisticsByLabel().values().stream()
                            .map(StoredStatistics::from)
                            .toList(),
                    summary.concurrentUsers(),
                    summary.throughputPerSecond());
        }

        RunSummary toDomain() {
            Map<String, SampleStatistics> byLabel = new LinkedHashMap<>();
            statistics.forEach(entry -> byLabel.put(entry.label(), entry.toDomain()));
            return new RunSummary(
                    runId, planFingerprint, Instant.parse(recordedAt),
                    byLabel, concurrentUsers, throughputPerSecond);
        }
    }

    record StoredStatistics(
            String label,
            long count,
            long failures,
            double meanMillis,
            long p50Millis,
            long p95Millis,
            long p99Millis,
            long maxMillis) {

        static StoredStatistics from(SampleStatistics statistics) {
            return new StoredStatistics(
                    statistics.label(), statistics.count(), statistics.failures(),
                    statistics.meanMillis(), statistics.p50Millis(), statistics.p95Millis(),
                    statistics.p99Millis(), statistics.maxMillis());
        }

        SampleStatistics toDomain() {
            return new SampleStatistics(
                    label, count, failures, meanMillis, p50Millis, p95Millis, p99Millis, maxMillis);
        }
    }
}
