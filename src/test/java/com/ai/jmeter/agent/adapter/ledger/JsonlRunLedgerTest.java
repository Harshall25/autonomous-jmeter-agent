package com.ai.jmeter.agent.adapter.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.controlplane.RunLedgerEntry;
import com.ai.jmeter.agent.domain.governance.TenantId;
import com.ai.jmeter.agent.domain.journal.HealJournal;
import com.ai.jmeter.agent.domain.journal.HealTurn;
import com.ai.jmeter.agent.domain.journal.PlanDiff;
import com.ai.jmeter.agent.port.ResultStoreException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("JsonlRunLedger")
class JsonlRunLedgerTest {

    private static final TenantId ACME = new TenantId("acme");
    private static final TenantId GLOBEX = new TenantId("globex");

    @TempDir
    Path workspace;

    private Path ledgerFile;
    private JsonlRunLedger ledger;

    @BeforeEach
    void setUp() {
        ledgerFile = workspace.resolve("run-ledger.jsonl");
        ledger = new JsonlRunLedger(new ObjectMapper(), ledgerFile);
    }

    private static RunLedgerEntry entry(String runId, Instant at, HealJournal journal) {
        return entry(runId, ACME, at, journal);
    }

    private static RunLedgerEntry entry(
            String runId, TenantId tenant, Instant at, HealJournal journal) {
        return new RunLedgerEntry(
                runId, tenant, at, ExecutionMode.API, journal.turnCount() + 1, 120,
                "plan-a", 4200, "Correlated the bearer token from /login", journal);
    }

    private static HealJournal journalWithOneTurn() {
        return HealJournal.empty().plus(new HealTurn(
                1, "401 Unauthorized", "The bearer token was never extracted",
                List.of("Add a JSONPath extractor for auth_token"), false,
                PlanDiff.between("<plan/>", "<plan>\n<extractor/>\n</plan>")));
    }

    @Test
    @DisplayName("reads back a run with every heal turn and its diff intact")
    void roundTripsAJournal() {
        ledger.record(entry("run-1", Instant.parse("2025-01-02T03:04:05Z"), journalWithOneTurn()));

        RunLedgerEntry stored = ledger.find(ACME, "run-1").orElseThrow();

        assertThat(stored.mode()).isEqualTo(ExecutionMode.API);
        assertThat(stored.attempts()).isEqualTo(2);
        assertThat(stored.totalTokens()).isEqualTo(4200);
        assertThat(stored.recordedAt()).isEqualTo(Instant.parse("2025-01-02T03:04:05Z"));
        assertThat(stored.journal().turns()).singleElement().satisfies(turn -> {
            assertThat(turn.diagnosis()).contains("bearer token");
            assertThat(turn.edits()).containsExactly("Add a JSONPath extractor for auth_token");
            assertThat(turn.fullRewrite()).isFalse();
            assertThat(turn.diff().addedLines()).isEqualTo(3);
            assertThat(turn.diff().unifiedLines()).isNotEmpty();
        });
    }

    @Test
    @DisplayName("keeps a run that needed no healing")
    void roundTripsACleanRun() {
        ledger.record(entry("run-clean", Instant.EPOCH, HealJournal.empty()));

        RunLedgerEntry stored = ledger.find(ACME, "run-clean").orElseThrow();

        assertThat(stored.healedFirstTime()).isTrue();
        assertThat(stored.journal().isEmpty()).isTrue();
        assertThat(stored.describe()).contains("run-clean").contains("120 sample(s)");
    }

    @Test
    @DisplayName("returns the most recent runs first")
    void ordersNewestFirst() {
        ledger.record(entry("older", Instant.parse("2025-01-01T00:00:00Z"), HealJournal.empty()));
        ledger.record(entry("newer", Instant.parse("2025-03-01T00:00:00Z"), HealJournal.empty()));

        assertThat(ledger.recent(ACME, 10).stream().map(RunLedgerEntry::runId))
                .containsExactly("newer", "older");
    }

    @Test
    @DisplayName("honours the page size it was asked for")
    void honoursTheLimit() {
        ledger.record(entry("a", Instant.parse("2025-01-01T00:00:00Z"), HealJournal.empty()));
        ledger.record(entry("b", Instant.parse("2025-02-01T00:00:00Z"), HealJournal.empty()));

        assertThat(ledger.recent(ACME, 1)).singleElement()
                .satisfies(run -> assertThat(run.runId()).isEqualTo("b"));
    }

    @Test
    @DisplayName("reports an empty ledger rather than failing on a first read")
    void missingLedgerIsEmpty() {
        assertThat(ledger.recent(ACME, 10)).isEmpty();
        assertThat(ledger.find(ACME, "anything")).isEmpty();
    }

    @Test
    @DisplayName("never hands one tenant another tenant's runs")
    void scopesReadsToTheTenant() {
        // Endpoint names and payload shapes are one organization's business. Scoping in the store
        // rather than at the caller is what makes it a filter nobody can forget to apply.
        ledger.record(entry("acme-run", ACME, Instant.EPOCH, HealJournal.empty()));
        ledger.record(entry("globex-run", GLOBEX, Instant.EPOCH, HealJournal.empty()));

        assertThat(ledger.recent(ACME, 10)).singleElement()
                .satisfies(run -> assertThat(run.runId()).isEqualTo("acme-run"));
        assertThat(ledger.recent(GLOBEX, 10)).singleElement()
                .satisfies(run -> assertThat(run.runId()).isEqualTo("globex-run"));
    }

    @Test
    @DisplayName("answers 'no such run' rather than 'not yours' for another tenant's run")
    void anotherTenantsRunIsSimplyAbsent() {
        // Distinguishing the two would let an outsider probe which run ids exist.
        ledger.record(entry("globex-run", GLOBEX, Instant.EPOCH, HealJournal.empty()));

        assertThat(ledger.find(ACME, "globex-run")).isEmpty();
        assertThat(ledger.find(GLOBEX, "globex-run")).isPresent();
    }

    @Test
    @DisplayName("reports no such run rather than inventing one")
    void unknownRunIsEmpty() {
        ledger.record(entry("run-1", Instant.EPOCH, HealJournal.empty()));

        assertThat(ledger.find(ACME, "run-2")).isEmpty();
    }

    @Test
    @DisplayName("skips blank lines a partial append can leave behind")
    void skipsBlankLines() throws IOException {
        ledger.record(entry("run-1", Instant.EPOCH, HealJournal.empty()));
        Files.writeString(ledgerFile, System.lineSeparator() + "   " + System.lineSeparator(),
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(ledger.recent(ACME, 10)).hasSize(1);
    }

    @Test
    @DisplayName("degrades to what parsed cleanly rather than losing the whole audit trail")
    void survivesACorruptEntry() throws IOException {
        ledger.record(entry("run-1", Instant.EPOCH, HealJournal.empty()));
        Files.writeString(ledgerFile, "{not json" + System.lineSeparator(),
                java.nio.file.StandardOpenOption.APPEND);

        assertThat(ledger.recent(ACME, 10)).singleElement()
                .satisfies(run -> assertThat(run.runId()).isEqualTo("run-1"));
    }

    @Test
    @DisplayName("reports a ledger it cannot write to")
    void reportsAnUnwritableLedger() throws IOException {
        Path blocked = workspace.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        JsonlRunLedger brittle =
                new JsonlRunLedger(new ObjectMapper(), blocked.resolve("nested/ledger.jsonl"));
        RunLedgerEntry entry = entry("run-1", Instant.EPOCH, HealJournal.empty());

        assertThatThrownBy(() -> brittle.record(entry))
                .isInstanceOf(ResultStoreException.class)
                .hasMessageContaining("Unable to record run in");
    }

    @Test
    @DisplayName("normalizes an entry the caller left half-filled")
    void normalizesSparseEntries() {
        RunLedgerEntry sparse = new RunLedgerEntry(
                "run-1", null, Instant.EPOCH, ExecutionMode.SQL, 1, 0, "plan", 0, null, null);

        assertThat(sparse.rationale()).isEmpty();
        assertThat(sparse.journal().isEmpty()).isTrue();
        assertThat(sparse.tenant()).isEqualTo(TenantId.local());
    }
}
