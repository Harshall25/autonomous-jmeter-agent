package com.ai.jmeter.agent.adapter.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ai.jmeter.agent.domain.ExecutionMode;
import com.ai.jmeter.agent.domain.controlplane.RunLedgerEntry;
import com.ai.jmeter.agent.domain.journal.HealJournal;
import com.ai.jmeter.agent.domain.journal.HealTurn;
import com.ai.jmeter.agent.domain.journal.PlanDiff;
import com.ai.jmeter.agent.port.RunLedgerPort;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@ExtendWith(MockitoExtension.class)
@DisplayName("ControlPlaneController")
class ControlPlaneControllerTest {

    @Mock
    private RunLedgerPort runLedger;

    private ControlPlaneController controller;

    @BeforeEach
    void setUp() {
        controller = new ControlPlaneController(runLedger);
    }

    private static RunLedgerEntry healedRun() {
        HealJournal journal = HealJournal.empty().plus(new HealTurn(
                1, "401 Unauthorized", "The bearer token was never extracted",
                List.of("Add a JSONPath extractor for auth_token"), false,
                PlanDiff.between("<plan/>", "<plan>\n<extractor/>\n</plan>")));
        return new RunLedgerEntry(
                "run-1", Instant.parse("2025-01-02T03:04:05Z"), ExecutionMode.API, 2, 120,
                "plan-a", 4200, "Correlated the bearer token from /login", journal);
    }

    @Nested
    @DisplayName("listing runs")
    class Listing {

        @Test
        @DisplayName("serves the headline of each run without its diffs")
        void listsWithoutDiffs() {
            when(runLedger.recent(anyInt())).thenReturn(List.of(healedRun()));

            List<RunView> runs = controller.recentRuns(25);

            assertThat(runs).singleElement().satisfies(run -> {
                assertThat(run.runId()).isEqualTo("run-1");
                assertThat(run.mode()).isEqualTo("API");
                assertThat(run.attempts()).isEqualTo(2);
                assertThat(run.healedFirstTime()).isFalse();
                assertThat(run.recordedAt()).isEqualTo("2025-01-02T03:04:05Z");
                assertThat(run.churn()).isEqualTo("+3/-1 line(s) across 1 turn(s)");
                // The list view is a menu, not a payload: a hundred runs of diffs is megabytes.
                assertThat(run.heals()).isEmpty();
            });
        }

        @Test
        @DisplayName("refuses to page an entire ledger into memory")
        void clampsThePageSize() {
            when(runLedger.recent(anyInt())).thenReturn(List.of());

            controller.recentRuns(100_000);

            ArgumentCaptor<Integer> limit = ArgumentCaptor.forClass(Integer.class);
            verify(runLedger).recent(limit.capture());
            assertThat(limit.getValue()).isEqualTo(200);
        }

        @Test
        @DisplayName("treats a nonsensical page size as one run")
        void clampsANonsensicalPageSize() {
            when(runLedger.recent(anyInt())).thenReturn(List.of());

            controller.recentRuns(-5);

            verify(runLedger).recent(1);
        }
    }

    @Nested
    @DisplayName("inspecting one run")
    class Detail {

        @Test
        @DisplayName("serves each heal turn with the rationale and the diff side by side")
        void servesHealTurns() {
            when(runLedger.find("run-1")).thenReturn(Optional.of(healedRun()));

            ResponseEntity<RunView> response = controller.run("run-1");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().rationale()).contains("Correlated the bearer token");
            assertThat(response.getBody().heals()).singleElement().satisfies(turn -> {
                assertThat(turn.attempt()).isEqualTo(1);
                assertThat(turn.strategy()).isEqualTo("1 structural edit(s)");
                assertThat(turn.diagnosis()).contains("bearer token");
                assertThat(turn.failureSignature()).isEqualTo("401 Unauthorized");
                assertThat(turn.edits()).hasSize(1);
                assertThat(turn.addedLines()).isEqualTo(3);
                assertThat(turn.removedLines()).isEqualTo(1);
                assertThat(turn.truncated()).isFalse();
                assertThat(turn.diff()).isNotEmpty();
            });
        }

        @Test
        @DisplayName("answers 404 for a run the ledger has never seen")
        void unknownRunIsNotFound() {
            when(runLedger.find(anyString())).thenReturn(Optional.empty());

            ResponseEntity<RunView> response = controller.run("missing");

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody()).isNull();
        }
    }
}
