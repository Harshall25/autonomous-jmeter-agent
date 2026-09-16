package com.ai.jmeter.agent.adapter.ledger;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.controlplane.RunLedgerEntry;
import com.ai.jmeter.agent.domain.governance.TenantId;
import com.ai.jmeter.agent.domain.journal.HealJournal;
import com.ai.jmeter.agent.domain.journal.HealTurn;
import com.ai.jmeter.agent.domain.journal.PlanDiff;
import com.ai.jmeter.agent.port.ResultStoreException;
import com.ai.jmeter.agent.port.RunLedgerPort;
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
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Driven adapter: append-only run ledger on the local filesystem.
 *
 * <p>The control plane needs somewhere to read runs back from, and requiring a database to see
 * what the agent did to a test plan would put the audit trail behind exactly the infrastructure a
 * team has not stood up yet. A JSONL file works on a laptop, survives a crash mid-append, and is
 * readable with {@code tail} when the API is not running.
 */
public final class JsonlRunLedger implements RunLedgerPort {

    private static final Logger log = LoggerFactory.getLogger(JsonlRunLedger.class);

    private final ObjectMapper objectMapper;
    private final Path ledgerFile;

    public JsonlRunLedger(ObjectMapper objectMapper, Path ledgerFile) {
        this.objectMapper = objectMapper;
        this.ledgerFile = ledgerFile;
    }

    @Override
    public void record(RunLedgerEntry entry) {
        try {
            Files.createDirectories(ledgerFile.toAbsolutePath().getParent());
            String line = objectMapper.writeValueAsString(StoredEntry.from(entry));
            Files.writeString(ledgerFile, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            log.info("Filed run {} in the control plane ledger", entry.runId());
        } catch (IOException e) {
            throw new ResultStoreException("Unable to record run in " + ledgerFile, e);
        }
    }

    @Override
    public List<RunLedgerEntry> recent(TenantId tenant, int limit) {
        return readAll().stream()
                .filter(entry -> entry.tenant().equals(tenant.value()))
                .map(StoredEntry::toDomain)
                .sorted(Comparator.comparing(RunLedgerEntry::recordedAt).reversed())
                .limit(limit)
                .toList();
    }

    @Override
    public Optional<RunLedgerEntry> find(TenantId tenant, String runId) {
        return readAll().stream()
                .filter(entry -> entry.runId().equals(runId))
                .filter(entry -> entry.tenant().equals(tenant.value()))
                .map(StoredEntry::toDomain)
                .findFirst();
    }

    private List<StoredEntry> readAll() {
        if (!Files.exists(ledgerFile)) {
            return List.of();
        }
        List<StoredEntry> entries = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(ledgerFile, StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    entries.add(objectMapper.readValue(line, new TypeReference<>() {
                    }));
                }
            }
        } catch (IOException e) {
            // One bad append must not take the whole audit trail with it.
            log.warn("Run ledger at {} is unreadable past entry {}", ledgerFile, entries.size(), e);
        }
        return entries;
    }

    /** The on-disk shape, kept separate so Jackson never touches a domain type. */
    record StoredEntry(
            String runId,
            String tenant,
            String recordedAt,
            String mode,
            int attempts,
            long totalSamples,
            String planFingerprint,
            long totalTokens,
            String rationale,
            List<StoredTurn> turns) {

        static StoredEntry from(RunLedgerEntry entry) {
            return new StoredEntry(
                    entry.runId(),
                    entry.tenant().value(),
                    entry.recordedAt().toString(),
                    entry.mode().name(),
                    entry.attempts(),
                    entry.totalSamples(),
                    entry.planFingerprint(),
                    entry.totalTokens(),
                    entry.rationale(),
                    entry.journal().turns().stream().map(StoredTurn::from).toList());
        }

        RunLedgerEntry toDomain() {
            return new RunLedgerEntry(
                    runId,
                    new TenantId(tenant),
                    Instant.parse(recordedAt),
                    ExecutionMode.valueOf(mode),
                    attempts,
                    totalSamples,
                    planFingerprint,
                    totalTokens,
                    rationale,
                    new HealJournal(turns.stream().map(StoredTurn::toDomain).toList()));
        }
    }

    record StoredTurn(
            int attempt,
            String failureSignature,
            String diagnosis,
            List<String> edits,
            boolean fullRewrite,
            List<String> diffLines,
            int addedLines,
            int removedLines,
            boolean truncated) {

        static StoredTurn from(HealTurn turn) {
            return new StoredTurn(
                    turn.attempt(),
                    turn.failureSignature(),
                    turn.diagnosis(),
                    turn.edits(),
                    turn.fullRewrite(),
                    turn.diff().unifiedLines(),
                    turn.diff().addedLines(),
                    turn.diff().removedLines(),
                    turn.diff().truncated());
        }

        HealTurn toDomain() {
            return new HealTurn(
                    attempt, failureSignature, diagnosis, edits, fullRewrite,
                    new PlanDiff(diffLines, addedLines, removedLines, truncated));
        }
    }
}
